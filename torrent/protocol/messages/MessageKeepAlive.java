package torrent.protocol.messages;

import torrent.download.peer.Peer;
import torrent.network.Stream;
import torrent.protocol.IMessage;

public class MessageKeepAlive implements IMessage {

	@Override
	public void write(Stream outStream) {
	}

	@Override
	public void read(Stream inStream) {
	}

	@Override
	public void process(Peer peer) {
		peer.updateLastActivity();
	}

	@Override
	public int getLength() {
		return 0;
	}

	@Override
	public int getId() {
		return 0;
	}

}
