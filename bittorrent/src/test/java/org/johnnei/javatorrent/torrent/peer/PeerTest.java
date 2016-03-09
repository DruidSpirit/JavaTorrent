package org.johnnei.javatorrent.torrent.peer;

import java.util.Optional;

import org.johnnei.javatorrent.bittorrent.protocol.messages.MessageChoke;
import org.johnnei.javatorrent.bittorrent.protocol.messages.MessageInterested;
import org.johnnei.javatorrent.bittorrent.protocol.messages.MessageUnchoke;
import org.johnnei.javatorrent.bittorrent.protocol.messages.MessageUninterested;
import org.johnnei.javatorrent.network.BitTorrentSocket;
import org.johnnei.javatorrent.test.DummyEntity;
import org.johnnei.javatorrent.torrent.Torrent;

import org.easymock.EasyMockRunner;
import org.easymock.EasyMockSupport;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.easymock.EasyMock.isA;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(EasyMockRunner.class)
public class PeerTest extends EasyMockSupport {

	@Test
	public void testAddModuleInfo() {
		Peer peer = new Peer(null, DummyEntity.createUniqueTorrent(), new byte[8]);

		Object o = new Object();
		peer.addModuleInfo(o);
		Object returnedO = peer.getModuleInfo(Object.class).get();

		assertEquals("Returned object is not equal to inserted", o, returnedO);
	}

	@Test(expected=IllegalStateException.class)
	public void testAddModuleInfoDuplicate() {
		Peer peer = new Peer(null, DummyEntity.createUniqueTorrent(), new byte[8]);

		Object o = new Object();
		Object o2 = new Object();
		peer.addModuleInfo(o);
		peer.addModuleInfo(o2);
	}

	@Test
	public void testAddModuleInfoNoElement() {
		Peer peer = new Peer(null, DummyEntity.createUniqueTorrent(), new byte[8]);

		Optional<Object> o = peer.getModuleInfo(Object.class);

		assertFalse("Expected empty result", o.isPresent());
	}

	@Test
	public void testDownloadInterested() {
		Torrent torrentMock = new Torrent.Builder()
				.setName("StubTorrent")
				.build();
		BitTorrentSocket socketMock = createMock(BitTorrentSocket.class);

		Peer peer = new Peer(socketMock, torrentMock, null);

		socketMock.enqueueMessage(isA(MessageInterested.class));

		replayAll();

		peer.setInterested(PeerDirection.Download, true);
		assertTrue("Incorrect interested state", peer.isInterested(PeerDirection.Download));

		verifyAll();
	}

	@Test
	public void testDownloadUninterested() {
		Torrent torrentMock = new Torrent.Builder()
				.setName("StubTorrent")
				.build();
		BitTorrentSocket socketMock = createMock(BitTorrentSocket.class);

		Peer peer = new Peer(socketMock, torrentMock, null);

		socketMock.enqueueMessage(isA(MessageUninterested.class));

		replayAll();

		peer.setInterested(PeerDirection.Download, false);
		assertFalse("Incorrect interested state", peer.isInterested(PeerDirection.Download));

		verifyAll();
	}

	@Test
	public void testUploadInterested() {
		Torrent torrentMock = new Torrent.Builder()
				.setName("StubTorrent")
				.build();
		BitTorrentSocket socketMock = createMock(BitTorrentSocket.class);

		Peer peer = new Peer(socketMock, torrentMock, null);

		replayAll();

		peer.setInterested(PeerDirection.Upload, true);
		assertTrue("Incorrect interested state", peer.isInterested(PeerDirection.Upload));
		peer.setInterested(PeerDirection.Upload, false);
		assertFalse("Incorrect interested state", peer.isInterested(PeerDirection.Upload));
	}

	@Test
	public void testDownloadChoke() {
		Torrent torrentMock = new Torrent.Builder()
				.setName("StubTorrent")
				.build();
		BitTorrentSocket socketMock = createMock(BitTorrentSocket.class);

		Peer peer = new Peer(socketMock, torrentMock, null);

		replayAll();

		peer.setChoked(PeerDirection.Download, true);
		assertTrue("Incorrect choked state", peer.isChoked(PeerDirection.Download));
		peer.setChoked(PeerDirection.Download, false);
		assertFalse("Incorrect choked state", peer.isChoked(PeerDirection.Download));

		verifyAll();
	}

	@Test
	public void testUploadChoke() {
		Torrent torrentMock = new Torrent.Builder()
				.setName("StubTorrent")
				.build();
		BitTorrentSocket socketMock = createMock(BitTorrentSocket.class);

		Peer peer = new Peer(socketMock, torrentMock, null);

		socketMock.enqueueMessage(isA(MessageChoke.class));

		replayAll();

		peer.setChoked(PeerDirection.Upload, true);
		assertTrue("Incorrect choked state", peer.isChoked(PeerDirection.Upload));

		verifyAll();
	}

	@Test
	public void testDownloadUnchoke() {
		Torrent torrentMock = new Torrent.Builder()
				.setName("StubTorrent")
				.build();
		BitTorrentSocket socketMock = createMock(BitTorrentSocket.class);

		Peer peer = new Peer(socketMock, torrentMock, null);

		socketMock.enqueueMessage(isA(MessageUnchoke.class));

		replayAll();

		peer.setChoked(PeerDirection.Upload, false);
		assertFalse("Incorrect choked state", peer.isChoked(PeerDirection.Upload));

		verifyAll();
	}
}
