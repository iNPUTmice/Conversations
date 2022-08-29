package eu.siacs.conversations.entities;

import androidx.annotation.NonNull;

import java.util.Locale;

import eu.siacs.conversations.xml.Element;

public class Presence implements Comparable<Presence> {

	public enum Status {
		CHAT, ONLINE, AWAY, XA, DND, OFFLINE;

		public String toShowString() {
			switch(this) {
				case CHAT: return "chat";
				case AWAY: return "away";
				case XA:   return "xa";
				case DND:  return "dnd";
			}
			return null;
		}

		public static Status fromShowString(String show) {
			if (show == null) {
				return ONLINE;
			} else {
				switch (show.toLowerCase(Locale.US)) {
					case "away":
						return AWAY;
					case "xa":
						return XA;
					case "dnd":
						return DND;
					case "chat":
						return CHAT;
					default:
						return ONLINE;
				}
			}
		}
	}

	private final Status status;
	private ServiceDiscoveryResult disco;
	private final String ver;
	private final String hash;
	private final String node;
	private final String message;

	private Presence(Status status, String ver, String hash, String node, String message) {
		this.status = status;
		this.ver = ver;
		this.hash = hash;
		this.node = node;
		this.message = message;
	}

	public static Presence parse(String show, Element caps, String message) {
		final String hash = caps == null ? null : caps.getAttribute("hash");
		final String ver = caps == null ? null : caps.getAttribute("ver");
		final String node = caps == null ? null : caps.getAttribute("node");
		return new Presence(Status.fromShowString(show), ver, hash, node, message);
	}

	public int compareTo(@NonNull Presence other) {
		return this.status.compareTo(other.status);
	}

	public Status getStatus() {
		return this.status;
	}

	public boolean hasCaps() {
		return ver != null && hash != null;
	}

	public String getVer() {
		return this.ver;
	}

	public String getNode() {
		return this.node;
	}

	public String getHash() {
		return this.hash;
	}

	public String getMessage() {
		return this.message;
	}

	public void setServiceDiscoveryResult(ServiceDiscoveryResult disco) {
		this.disco = disco;
	}

	public ServiceDiscoveryResult getServiceDiscoveryResult() {
		return disco;
	}
}
