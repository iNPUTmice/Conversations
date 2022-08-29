package eu.siacs.conversations.xmpp.jingle;

import java.util.ArrayList;
import java.util.List;

import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xmpp.InvalidJid;
import eu.siacs.conversations.xmpp.Jid;

public class JingleCandidate {

	public static int TYPE_UNKNOWN;
	public static int TYPE_DIRECT = 0;
	public static int TYPE_PROXY = 1;

	private final boolean ours;
	private boolean usedByCounterpart = false;
	private final String cid;
	private String host;
	private int port;
	private int type;
	private Jid jid;
	private int priority;

	public JingleCandidate(String cid, boolean ours) {
		this.ours = ours;
		this.cid = cid;
	}

	public String getCid() {
		return cid;
	}

	public void setHost(String host) {
		this.host = host;
	}

	public String getHost() {
		return this.host;
	}

	public void setJid(final Jid jid) {
		this.jid = jid;
	}

	public Jid getJid() {
		return this.jid;
	}

	public void setPort(int port) {
		this.port = port;
	}

	public int getPort() {
		return this.port;
	}

	public void setType(int type) {
		this.type = type;
	}

	public void setType(String type) {
		if (type == null) {
			this.type = TYPE_UNKNOWN;
			return;
		}
        switch (type) {
            case "proxy":
                this.type = TYPE_PROXY;
                break;
            case "direct":
                this.type = TYPE_DIRECT;
                break;
            default:
                this.type = TYPE_UNKNOWN;
                break;
        }
	}

	public void setPriority(int i) {
		this.priority = i;
	}

	public int getPriority() {
		return this.priority;
	}

	public boolean equals(JingleCandidate other) {
		return this.getCid().equals(other.getCid());
	}

	public boolean equalValues(JingleCandidate other) {
		return other != null && other.getHost().equals(this.getHost()) && (other.getPort() == this.getPort());
	}

	public boolean isOurs() {
		return ours;
	}

	public int getType() {
		return this.type;
	}

	public static List<JingleCandidate> parse(final List<Element> elements) {
		final List<JingleCandidate> candidates = new ArrayList<>();
		for (final Element element : elements) {
			if ("candidate".equals(element.getName())) {
				candidates.add(JingleCandidate.parse(element));
			}
		}
		return candidates;
	}

	public static JingleCandidate parse(Element element) {
		final JingleCandidate candidate = new JingleCandidate(element.getAttribute("cid"), false);
		candidate.setHost(element.getAttribute("host"));
		candidate.setJid(InvalidJid.getNullForInvalid(element.getAttributeAsJid("jid")));
		candidate.setType(element.getAttribute("type"));
		candidate.setPriority(Integer.parseInt(element.getAttribute("priority")));
		candidate.setPort(Integer.parseInt(element.getAttribute("port")));
		return candidate;
	}

	public Element toElement() {
		Element element = new Element("candidate");
		element.setAttribute("cid", this.getCid());
		element.setAttribute("host", this.getHost());
		element.setAttribute("port", Integer.toString(this.getPort()));
		if (jid != null) {
			element.setAttribute("jid", jid);
		}
		element.setAttribute("priority", Integer.toString(this.getPriority()));
		if (this.getType() == TYPE_DIRECT) {
			element.setAttribute("type", "direct");
		} else if (this.getType() == TYPE_PROXY) {
			element.setAttribute("type", "proxy");
		}
		return element;
	}

	public void flagAsUsedByCounterpart() {
		this.usedByCounterpart = true;
	}

	public boolean isUsedByCounterpart() {
		return this.usedByCounterpart;
	}

	public String toString() {
		return String.format("%s:%s (priority=%s,ours=%s)", getHost(), getPort(), getPriority(), isOurs());
	}
}
