package eu.siacs.conversations.xmpp.stanzas;

import android.util.Pair;

import eu.siacs.conversations.parser.AbstractParser;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.LocalizedContent;

public class MessagePacket extends AbstractAcknowledgeableStanza {
	public static final int TYPE_CHAT = 0;
	public static final int TYPE_NORMAL = 2;
	public static final int TYPE_GROUPCHAT = 3;
	public static final int TYPE_ERROR = 4;
	public static final int TYPE_HEADLINE = 5;

	public MessagePacket() {
		super("message");
	}

	public LocalizedContent getBody() {
		return findInternationalizedChildContentInDefaultNamespace("body");
	}

	public void setBody(String text) {
		removeChild(findChild("body"));
		prependChild(new Element("body").setContent(text));
	}

	public void setAxolotlMessage(Element axolotlMessage) {
		removeChild(findChild("body"));
		prependChild(axolotlMessage);
	}

	public void setType(int type) {
		switch (type) {
		case TYPE_CHAT:
			this.setAttribute("type", "chat");
			break;
		case TYPE_GROUPCHAT:
			this.setAttribute("type", "groupchat");
			break;
		case TYPE_NORMAL:
			break;
		case TYPE_ERROR:
			this.setAttribute("type","error");
			break;
		default:
			this.setAttribute("type", "chat");
			break;
		}
	}

	public int getType() {
		String type = getAttribute("type");
		if (type == null) {
			return TYPE_NORMAL;
		} else if (type.equals("normal")) {
			return TYPE_NORMAL;
		} else if (type.equals("chat")) {
			return TYPE_CHAT;
		} else if (type.equals("groupchat")) {
			return TYPE_GROUPCHAT;
		} else if (type.equals("error")) {
			return TYPE_ERROR;
		} else if (type.equals("headline")) {
			return TYPE_HEADLINE;
		} else {
			return TYPE_NORMAL;
		}
	}

	public Pair<MessagePacket,Long> getForwardedMessagePacket(String name, String namespace) {
		Element wrapper = findChild(name, namespace);
		if (wrapper == null) {
			return null;
		}
		Element forwarded = wrapper.findChild("forwarded", "urn:xmpp:forward:0");
		if (forwarded == null) {
			return null;
		}
		MessagePacket packet = create(forwarded.findChild("message"));
		if (packet == null) {
			return null;
		}
		Long timestamp = AbstractParser.parseTimestamp(forwarded, null);
		return new Pair(packet,timestamp);
	}

	public static MessagePacket create(Element element) {
		if (element == null) {
			return null;
		}
		MessagePacket packet = new MessagePacket();
		packet.setAttributes(element.getAttributes());
		packet.setChildren(element.getChildren());
		return packet;
	}
}
