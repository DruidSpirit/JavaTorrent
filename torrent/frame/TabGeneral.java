package torrent.frame;

import java.awt.Color;
import java.awt.Graphics;

import javax.swing.JPanel;

import torrent.download.Torrent;
import torrent.util.StringUtil;

public class TabGeneral extends JPanel {

	public static final long serialVersionUID = 1L;

	private Torrent torrent;

	public void setTorrent(Torrent torrent) {
		this.torrent = torrent;
	}

	public void paintComponent(Graphics g) {
		super.paintComponent(g);
		String name = "Name: ";
		String hash = "Hash: ";
		String totalSize = "Total Size: ";
		String leftSize =  "Remaining: ";
		String pieces = "Pieces: ";
		String peers = "Peers: ";
		String leechers = "Leechers: ";
		String pending = "Pending Connections: ";
		String download = "Download Speed: ";
		String upload = "Upload Speed: ";

		if (torrent != null) {
			name += torrent.getDisplayName();
			hash += torrent.getHash();
			if (torrent.getDownloadStatus() == Torrent.STATE_DOWNLOAD_METADATA) {
				totalSize += "Retrieving metadata";
				pieces += "Retrieving metadata";
				leftSize += "Retrieving metadata";
			} else {
				totalSize += torrent.getFiles().getTotalSize() + " bytes";
				pieces += torrent.getFiles().getPieceCount();
				leftSize += torrent.getFiles().getRemainingBytes() + " bytes";
			}
			pending += torrent.getConnectingCount();
			peers += torrent.getSeedCount();
			leechers += torrent.getLeecherCount();
			download += StringUtil.speedToString(torrent.getDownloadRate());
			upload += StringUtil.speedToString(torrent.getUploadRate());
		}

		double progress = 0;
		if (torrent != null) {
				progress = torrent.getProgress();
		}
		g.setColor(Color.GRAY);
		g.drawRect(10, 10, getWidth() - 20, 10);
		if(torrent != null) {
			if(torrent.getDownloadStatus() == Torrent.STATE_DOWNLOAD_METADATA)
				g.setColor(Color.ORANGE);
			else
				g.setColor(Color.GREEN);
		} else
		g.setColor(Color.GREEN);
		g.fillRect(11, 11, (int) (progress * 0.01 * (getWidth() - 21)), 9);

		g.setColor(Color.BLACK);
		g.drawString(name, 10, 50);
		g.drawString(hash, 10, 75);
		g.drawString(totalSize, 10, 100);
		g.drawString(leftSize, 10, 125);
		g.drawString(pieces, 10, 150);

		int x = getWidth() / 2;
		g.drawString(peers, x, 50);
		g.drawString(leechers, x, 75);
		g.drawString(pending, x, 100);
		g.drawString(download, x, 125);
		g.drawString(upload, x, 150);
	}

}
