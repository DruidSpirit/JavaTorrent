package org.johnnei.javatorrent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.johnnei.javatorrent.async.LoopingRunnable;
import org.johnnei.javatorrent.bittorrent.protocol.MessageFactory;
import org.johnnei.javatorrent.bittorrent.protocol.messages.IMessage;
import org.johnnei.javatorrent.bittorrent.tracker.ITracker;
import org.johnnei.javatorrent.bittorrent.tracker.TrackerException;
import org.johnnei.javatorrent.bittorrent.tracker.TrackerFactory;
import org.johnnei.javatorrent.disk.IDiskJob;
import org.johnnei.javatorrent.internal.TorrentClientSettingsImpl;
import org.johnnei.javatorrent.internal.disk.IOManager;
import org.johnnei.javatorrent.internal.network.PeerIoHandler;
import org.johnnei.javatorrent.internal.network.connector.BitTorrentHandshakeHandlerImpl;
import org.johnnei.javatorrent.internal.torrent.TorrentManager;
import org.johnnei.javatorrent.internal.tracker.TrackerManager;
import org.johnnei.javatorrent.module.IModule;
import org.johnnei.javatorrent.network.ConnectionDegradation;
import org.johnnei.javatorrent.network.connector.BitTorrentHandshakeHandler;
import org.johnnei.javatorrent.phases.PhaseRegulator;
import org.johnnei.javatorrent.torrent.Torrent;
import org.johnnei.javatorrent.torrent.algos.requests.IRequestLimiter;
import org.johnnei.javatorrent.tracker.IPeerConnector;
import org.johnnei.javatorrent.tracker.IPeerDistributor;
import org.johnnei.javatorrent.utils.Argument;
import org.johnnei.javatorrent.utils.CheckedBiFunction;

/**
 * The Torrent Client is the main entry point for the configuration and initiation of downloads/uploads.
 *
 */
public class TorrentClient {

	private static final Logger LOGGER = LoggerFactory.getLogger(TorrentClient.class);

	private ConnectionDegradation connectionDegradation;

	private MessageFactory messageFactory;

	private TorrentManager torrentManager;

	private TrackerManager trackerManager;

	private PhaseRegulator phaseRegulator;

	private IPeerConnector peerConnector;

	private IPeerDistributor peerDistributor;

	private IRequestLimiter requestLimiter;

	private ScheduledExecutorService executorService;

	private IOManager ioManager;

	private LoopingRunnable ioManagerRunner;

	private final byte[] extensionBytes;

	private final byte[] peerId;

	private AtomicInteger transactionId;

	private Collection<IModule> modules;

	private BitTorrentHandshakeHandlerImpl handshakeHandler;

	private PeerIoHandler peerIoHandler;

	private TorrentClientSettings settings;

	private TorrentClient(Builder builder) {
		settings = builder.settingsBuilder.build();
		peerDistributor = Objects.requireNonNull(builder.peerDistributor.apply(this), "Peer distributor is invalid.");
		connectionDegradation = Objects.requireNonNull(builder.connectionDegradation, "Connection degradation is required to setup connections with peers.");
		LOGGER.info("Configured connection types: {}", connectionDegradation);
		messageFactory = builder.messageFactoryBuilder.build();
		phaseRegulator = Objects.requireNonNull(builder.phaseRegulator, "Phase regulator is required to regulate the download/seed phases of a torrent.");
		LOGGER.info("Configured phases: {}", phaseRegulator);
		executorService = Objects.requireNonNull(builder.executorService, "Executor service is required to process torrent tasks.");
		requestLimiter = Objects.requireNonNull(builder.requestLimiter, "Request Limiter is required to improve transfer rates.");

		peerConnector = Objects.requireNonNull(builder.peerConnector, "Peer connector required to allow external connections").apply(this);
		LOGGER.info("Configured {} as Peer Connector", peerConnector);

		Objects.requireNonNull(builder.trackerFactoryBuilder, "At least one tracker protocol must be configured.");
		TrackerFactory trackerFactory = builder.trackerFactoryBuilder.setTorrentClient(this).build();

		trackerManager = new TrackerManager(peerConnector, trackerFactory);
		torrentManager = new TorrentManager(trackerManager);
		LOGGER.info("Configured trackers: {}", trackerFactory);

		modules = builder.modules;
		LOGGER.info("Configured modules: {}", modules.stream()
				.map(m -> String.format("%s (BEP %d)", m.getClass().getSimpleName(), m.getRelatedBep()))
				.reduce((a, b) -> a + ", " + b).orElse(""));

		extensionBytes = builder.extensionBytes;
		peerId = createPeerId();
		transactionId = new AtomicInteger(new Random().nextInt());

		peerIoHandler = new PeerIoHandler(executorService);
		handshakeHandler = new BitTorrentHandshakeHandlerImpl(this, peerIoHandler);

		ioManager = new IOManager();
		ioManagerRunner = new LoopingRunnable(ioManager, true);
		Thread ioManagerThread = new Thread(ioManagerRunner, String.format("Disk Manager - %s", ioManager.toString()));
		ioManagerThread.setDaemon(true);
		ioManagerThread.start();

		torrentManager.start(this);
		if (settings.isAcceptingConnections()) {
			torrentManager.enableConnectionAcceptor();
		}
		peerConnector.start();
	}

	private byte[] createPeerId() {
		char[] version = String.format("%s%2s%s", Version.VERSION_MAJOR, Version.VERSION_MINOR, Version.VERSION_PATCH).replace(" ", "0").toCharArray();
		byte[] newPeerId = new byte[20];
		newPeerId[0] = '-';
		newPeerId[1] = 'J';
		newPeerId[2] = 'T';
		newPeerId[3] = (byte) version[0];
		newPeerId[4] = (byte) version[1];
		newPeerId[5] = (byte) version[2];
		newPeerId[6] = (byte) version[3];
		newPeerId[7] = '-';

		Random random = new Random();
		for (int i = 8; i < newPeerId.length; i++) {
			newPeerId[i] = (byte) (random.nextInt() & 0xFF);
		}
		return newPeerId;
	}

	/**
	 * Initiates the downloading of a torrent.
	 * @param torrent The torrent to download.
	 * @param trackerUrls The trackers which are known for this torrent.
	 */
	public void download(Torrent torrent, Collection<String> trackerUrls) {
		trackerUrls.forEach(url -> trackerManager.addTorrent(torrent, url));
		download(torrent);
	}

	/**
	 * Initiates the downloading of a torrent.
	 * @param torrent The torrent to download.
	 */
	public void download(Torrent torrent) {
		torrentManager.addTorrent(torrent);
	}

	/**
	 * Shuts down all components of the TorrentClient.
	 */
	public void shutdown() {
		peerConnector.stop();
		handshakeHandler.stop();
		torrentManager.stop();
		ioManagerRunner.stop();
		peerIoHandler.shutdown();
		executorService.shutdown();
		modules.stream().forEach(IModule::onShutdown);
	}

	public int createUniqueTransactionId() {
		return transactionId.incrementAndGet();
	}

	/**
	 * Submits the disk job to be processed by the IO manager.
	 * @param task The disk job to execute.
	 */
	public void addDiskJob(IDiskJob task) {
		ioManager.addTask(task);
	}

	/**
	 * Calculates how many connections are assigned to the torrent but haven't passed the BitTorrent handshake yet.
	 * @param torrent The torrent for which connections must be counted.
	 * @return The amount of pending peers.
	 */
	public int getConnectingCountFor(Torrent torrent) {
		return trackerManager.getConnectingCountFor(torrent);
	}

	/**
	 * Gets all trackers which know the given torrent
	 * @param torrent the torrent which the tracker must support
	 * @return a collection of trackers which support the given torrent
	 */
	public List<ITracker> getTrackersFor(Torrent torrent) {
		return trackerManager.getTrackersFor(torrent);
	}

	/**
	 * Gets the message factory for this client
	 * @return The {@link MessageFactory}
	 */
	public MessageFactory getMessageFactory() {
		return messageFactory;
	}

	/**
	 * Gets the configured connection degradation rules.
	 * @return The socket degradation rules.
	 */
	public ConnectionDegradation getConnectionDegradation() {
		return connectionDegradation;
	}

	/**
	 * Gets the {@link PhaseRegulator} which manages the ordering of the download states.
	 * @return The configured phase regulator.
	 */
	public PhaseRegulator getPhaseRegulator() {
		return phaseRegulator;
	}

	/**
	 * Gets the {@link ScheduledExecutorService} which will execute the small tasks
	 * @return The executor service implementation
	 */
	public ScheduledExecutorService getExecutorService() {
		return executorService;
	}

	/**
	 * Gets the {@link IPeerConnector} which connects new peers
	 * @return The peer connector implementation
	 */
	public IPeerConnector getPeerConnector() {
		return peerConnector;
	}

	/**
	 * Gets the port at which we are listening for peers
	 * @return The port at which we are listening
	 * @deprecated Use {@link TorrentClientSettings#getAcceptingPort()} instead.
	 */
	@Deprecated
	public int getDownloadPort() {
		return settings.getAcceptingPort();
	}

	/**
	 * Gets the eight extension bytes which represent which BitTorrent extensions are enabled on this client.
	 * @return The extension bytes
	 */
	public byte[] getExtensionBytes() {
		return Arrays.copyOf(extensionBytes, extensionBytes.length);
	}

	/**
	 * Gets the 20-byte peer ID associated to this tracker manager
	 *
	 * @return The peer ID
	 */
	public byte[] getPeerId() {
		return peerId;
	}

	/**
	 * Gets the module instance if it is registered to this Torrent Client.
	 * @param type The module class.
	 * @param <T> The module type.
	 * @return The module instance or {@link Optional#empty()} if not found.
	 */
	@SuppressWarnings("unchecked")
	public <T extends IModule> Optional<T> getModule(Class<T> type) {
		return (Optional<T>) modules.stream().filter(m -> m.getClass().equals(type)).findAny();
	}

	/**
	 * Gets an unmodifiable view of the registered modules to this torrent client.
	 * @return The collection of modules.
	 */
	public Collection<IModule> getModules() {
		return Collections.unmodifiableCollection(modules);
	}

	/**
	 * Gets the torrent associated with the given hash.
	 * @param torrentHash The BTIH of the torrent
	 * @return The torrent if known.
	 */
	public Optional<Torrent> getTorrentByHash(byte[] torrentHash) {
		return torrentManager.getTorrent(torrentHash);
	}

	/**
	 * @return The configured {@link IPeerDistributor} for this {@link TorrentClient}
	 */
	public IPeerDistributor getPeerDistributor() {
		return peerDistributor;
	}

	/**
	 * @return The amount of torrents that are being downloaded.
	 */
	public int getTorrentCount() {
		return torrentManager.getTorrents().size();
	}

	/**
	 * @return The configured limiter.
	 */
	public IRequestLimiter getRequestLimiter() {
		return requestLimiter;
	}

	/**
	 * @return A handler instance which is able to process the BitTorrent handshake on freshly connected sockets.
	 */
	public BitTorrentHandshakeHandler getHandshakeHandler() {
		return handshakeHandler;
	}

	/**
	 * @return The configuration of the Torrent Client
	 */
	public TorrentClientSettings getSettings() {
		return settings;
	}

	public static class Builder {

		private final MessageFactory.Builder messageFactoryBuilder;

		private final Collection<IModule> modules;

		private ConnectionDegradation connectionDegradation;

		private PhaseRegulator phaseRegulator;

		private TrackerFactory.Builder trackerFactoryBuilder;

		private Function<TorrentClient, IPeerConnector> peerConnector;

		private Function<TorrentClient, IPeerDistributor> peerDistributor;

		private ScheduledExecutorService executorService;

		private IRequestLimiter requestLimiter;

		private byte[] extensionBytes;

		private TorrentClientSettingsImpl.Builder settingsBuilder = new TorrentClientSettingsImpl.Builder();

		public Builder() {
			messageFactoryBuilder = new MessageFactory.Builder();
			trackerFactoryBuilder = new TrackerFactory.Builder();
			modules = new ArrayList<>();
			extensionBytes = new byte[8];
		}

		public Builder registerModule(IModule module) {
			for (Class<IModule> dependingModule : module.getDependsOn()) {
				if (!modules.stream().anyMatch(m -> m.getClass().equals(dependingModule))) {
					throw new IllegalStateException(String.format("Depending module %s is missing.", dependingModule.getSimpleName()));
				}
			}

			module.configureTorrentClient(this);
			modules.add(module);
			return this;
		}

		/**
		 * Enables a bit in the extension bytes. According to BEP 3 there are 8 extension bytes (reserved bytes).
		 * The bit numbers are represented in the following order: Right to left, starting at zero.
		 * For reference see BEP 10 which indicates that bit 20 must be enabled.
		 * @param bit The bit to enable.
		 */
		public Builder enableExtensionBit(int bit) {
			final int index = extensionBytes.length - 1 - (bit / 8);
			final int bitValue = 1 << Math.floorMod(bit, 8);

			extensionBytes[index] |= bitValue;
			return this;
		}

		/**
		 * Enables (or disables) the starting of the peer connection accepts upon building of the torrent client.
		 * By default this is disabled.
		 * @param acceptIncomingConnections <code>true</code> when connections should be accepted.
		 * @return this
		 */
		public Builder acceptIncomingConnections(boolean acceptIncomingConnections) {
			settingsBuilder.withAcceptingConnections(acceptIncomingConnections);
			return this;
		}

		public Builder registerMessage(int id, Supplier<IMessage> messageSupplier) {
			messageFactoryBuilder.registerMessage(id, messageSupplier);
			return this;
		}

		public Builder registerTrackerProtocol(String protocol, CheckedBiFunction<String, TorrentClient, ITracker, TrackerException> supplier) {
			trackerFactoryBuilder.registerProtocol(protocol, supplier);
			return this;
		}

		public Builder setPhaseRegulator(PhaseRegulator phaseRegulator) {
			this.phaseRegulator = phaseRegulator;
			return this;
		}

		public Builder setConnectionDegradation(ConnectionDegradation connectionDegradation) {
			this.connectionDegradation = connectionDegradation;
			return this;
		}

		public Builder setPeerConnector(Function<TorrentClient, IPeerConnector> peerConnector) {
			this.peerConnector = peerConnector;
			return this;
		}

		public Builder setExecutorService(ScheduledExecutorService executorService) {
			this.executorService = executorService;
			return this;
		}

		public Builder setRequestLimiter(IRequestLimiter requestLimiter) {
			this.requestLimiter = requestLimiter;
			return this;
		}

		/**
		 * Sets the download port at which we are listening
		 * @param downloadPort The port at which we are listening
		 * @return The modified instance
		 */
		public Builder setDownloadPort(int downloadPort) {
			settingsBuilder.withAcceptingPort(downloadPort);
			return this;
		}

		/**
		 * Sets the peer distributor which is being used by the {@link IPeerConnector} implementation.
		 * @param peerDistributor The peer distributor.
		 */
		public Builder setPeerDistributor(Function<TorrentClient, IPeerDistributor> peerDistributor) {
			Argument.requireNonNull(peerDistributor, "Peer distributors cannot be null");
			this.peerDistributor = peerDistributor;
			return this;
		}

		public TorrentClient build() throws Exception {
			TorrentClient client = new TorrentClient(this);
			for (IModule module : modules) {
				module.onBuild(client);
			}
			return client;
		}
	}

}
