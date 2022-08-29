package eu.siacs.conversations.xmpp.pep;

import android.util.Base64;

import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xmpp.Jid;

public class Avatar {

	public enum Origin { PEP, VCARD }

    public String type;
	public String sha1sum;
	public String image;
	public int height;
	public int width;
	public long size;
	public Jid owner;
	public Origin origin = Origin.PEP; //default to maintain compat

	public byte[] getImageAsBytes() {
		return Base64.decode(image, Base64.DEFAULT);
	}

	public String getFilename() {
		return sha1sum;
	}

	public static Avatar parseMetadata(Element items) {
		Element item = items.findChild("item");
		if (item == null) {
			return null;
		}
		Element metadata = item.findChild("metadata");
		if (metadata == null) {
			return null;
		}
		String primaryId = item.getAttribute("id");
		if (primaryId == null) {
			return null;
		}
		for (Element child : metadata.getChildren()) {
			if (child.getName().equals("info")
					&& primaryId.equals(child.getAttribute("id"))) {
				Avatar avatar = new Avatar();
				String height = child.getAttribute("height");
				String width = child.getAttribute("width");
				String size = child.getAttribute("bytes");
				try {
					if (height != null) {
						avatar.height = Integer.parseInt(height);
					}
					if (width != null) {
						avatar.width = Integer.parseInt(width);
					}
					if (size != null) {
						avatar.size = Long.parseLong(size);
					}
				} catch (NumberFormatException e) {
					return null;
				}
				avatar.type = child.getAttribute("type");
				String hash = child.getAttribute("id");
				if (!isValidSHA1(hash)) {
					return null;
				}
				avatar.sha1sum = hash;
				avatar.origin = Origin.PEP;
				return avatar;
			}
		}
		return null;
	}

	@Override
	public boolean equals(Object object) {
		if (object != null && object instanceof Avatar) {
			Avatar other = (Avatar) object;
			return other.getFilename().equals(this.getFilename());
		} else {
			return false;
		}
	}

	public static Avatar parsePresence(Element x) {
		String hash = x == null ? null : x.findChildContent("photo");
		if (hash == null) {
			return null;
		}
		if (!isValidSHA1(hash)) {
			return null;
		}
		Avatar avatar = new Avatar();
		avatar.sha1sum = hash;
		avatar.origin = Origin.VCARD;
		return avatar;
	}

	private static boolean isValidSHA1(String s) {
		return s != null && s.matches("[a-fA-F0-9]{40}");
	}
}
