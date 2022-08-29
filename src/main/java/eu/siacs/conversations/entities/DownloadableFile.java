package eu.siacs.conversations.entities;

import android.util.Log;

import java.io.File;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.utils.MimeUtils;

public class DownloadableFile extends File {

	private static final long serialVersionUID = 2247012619505115863L;

	private long expectedSize = 0;
	private byte[] sha1sum;
	private byte[] aeskey;
	private byte[] iv;

	public DownloadableFile(final File parent, final String file) {
		super(parent, file);
	}

	public DownloadableFile(String path) {
		super(path);
	}

	public long getSize() {
		return super.length();
	}

	public long getExpectedSize() {
		return this.expectedSize;
	}

	public String getMimeType() {
		String path = this.getAbsolutePath();
		int start = path.lastIndexOf('.') + 1;
		if (start < path.length()) {
			String mime = MimeUtils.guessMimeTypeFromExtension(path.substring(start));
			return mime == null ? "" : mime;
		} else {
			return "";
		}
	}

	public void setExpectedSize(long size) {
		this.expectedSize = size;
	}

	public byte[] getSha1Sum() {
		return this.sha1sum;
	}

	public void setSha1Sum(byte[] sum) {
		this.sha1sum = sum;
	}

	public void setKeyAndIv(byte[] keyIvCombo) {
		// originally, we used a 16 byte IV, then found for aes-gcm a 12 byte IV is ideal
		// this code supports reading either length, with sending 12 byte IV to be done in future
		if (keyIvCombo.length == 48) {
			this.aeskey = new byte[32];
			this.iv = new byte[16];
			System.arraycopy(keyIvCombo, 0, this.iv, 0, 16);
			System.arraycopy(keyIvCombo, 16, this.aeskey, 0, 32);
		} else if (keyIvCombo.length == 44) {
			this.aeskey = new byte[32];
			this.iv = new byte[12];
			System.arraycopy(keyIvCombo, 0, this.iv, 0, 12);
			System.arraycopy(keyIvCombo, 12, this.aeskey, 0, 32);
		} else if (keyIvCombo.length >= 32) {
			this.aeskey = new byte[32];
			this.iv = new byte[]{ 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0xf };
			System.arraycopy(keyIvCombo, 0, aeskey, 0, 32);
		}
		Log.d(Config.LOGTAG,"using "+this.iv.length+"-byte IV for file transmission");
	}

	public void setKey(byte[] key) {
		this.aeskey = key;
	}

	public void setIv(byte[] iv) {
		this.iv = iv;
	}

	public byte[] getKey() {
		return this.aeskey;
	}

	public byte[] getIv() {
		return this.iv;
	}
}
