package eu.siacs.conversations.parser;


import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xmpp.InvalidJid;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.stanzas.AbstractStanza;

public abstract class AbstractParser {

	protected XmppConnectionService mXmppConnectionService;

	protected AbstractParser(XmppConnectionService service) {
		this.mXmppConnectionService = service;
	}

	public static Long parseTimestamp(Element element, Long d) {
		return parseTimestamp(element,d,false);
	}

	public static Long parseTimestamp(Element element, Long d, boolean ignoreCsiAndSm) {
		long min = Long.MAX_VALUE;
		boolean returnDefault = true;
		final Jid to;
		if (ignoreCsiAndSm && element instanceof AbstractStanza) {
			to = ((AbstractStanza) element).getTo();
		} else {
			to = null;
		}
		for(Element child : element.getChildren()) {
			if ("delay".equals(child.getName()) && "urn:xmpp:delay".equals(child.getNamespace())) {
				final Jid f = to == null ? null : InvalidJid.getNullForInvalid(child.getAttributeAsJid("from"));
				if (f != null && (to.asBareJid().equals(f) || to.getDomain().equals(f))) {
					continue;
				}
				final String stamp = child.getAttribute("stamp");
				if (stamp != null) {
					try {
						min = Math.min(min,AbstractParser.parseTimestamp(stamp));
						returnDefault = false;
					} catch (Throwable t) {
						//ignore
					}
				}
			}
		}
		if (returnDefault) {
			return d;
		} else {
			return min;
		}
	}

	public static long parseTimestamp(Element element) {
		return parseTimestamp(element, System.currentTimeMillis());
	}

	public static long parseTimestamp(String timestamp) throws ParseException {
		timestamp = timestamp.replace("Z", "+0000");
		SimpleDateFormat dateFormat;
		long ms;
		if (timestamp.length() >= 25 && timestamp.charAt(19) == '.') {
			String millis = timestamp.substring(19,timestamp.length() - 5);
			try {
				double fractions = Double.parseDouble("0" + millis);
				ms = Math.round(1000 * fractions);
			} catch (NumberFormatException e) {
				ms = 0;
			}
		} else {
			ms = 0;
		}
		timestamp = timestamp.substring(0,19)+timestamp.substring(timestamp.length() -5);
		dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ",Locale.US);
		return Math.min(dateFormat.parse(timestamp).getTime()+ms, System.currentTimeMillis());
	}

	protected void updateLastseen(final Account account, final Jid from) {
		final Contact contact = account.getRoster().getContact(from);
		contact.setLastResource(from.isBareJid() ? "" : from.getResource());
	}

	protected String avatarData(Element items) {
		Element item = items.findChild("item");
		if (item == null) {
			return null;
		}
		return item.findChildContent("data", "urn:xmpp:avatar:data");
	}

	public static MucOptions.User parseItem(Conversation conference, Element item) {
		return parseItem(conference,item, null);
	}

	public static MucOptions.User parseItem(Conversation conference, Element item, Jid fullJid) {
		final String local = conference.getJid().getLocal();
		final String domain = conference.getJid().getDomain().toEscapedString();
		String affiliation = item.getAttribute("affiliation");
		String role = item.getAttribute("role");
		String nick = item.getAttribute("nick");
		if (nick != null && fullJid == null) {
			try {
				fullJid = Jid.of(local, domain, nick);
			} catch (IllegalArgumentException e) {
				fullJid = null;
			}
		}
		Jid realJid = item.getAttributeAsJid("jid");
		MucOptions.User user = new MucOptions.User(conference.getMucOptions(), fullJid);
		if (InvalidJid.isValid(realJid)) {
			user.setRealJid(realJid);
		}
		user.setAffiliation(affiliation);
		user.setRole(role);
		return user;
	}

	public static String extractErrorMessage(final Element packet) {
		final Element error = packet.findChild("error");
		if (error != null && error.getChildren().size() > 0) {
			final List<String> errorNames = orderedElementNames(error.getChildren());
			final String text = error.findChildContent("text");
			if (text != null && !text.trim().isEmpty()) {
				return prefixError(errorNames)+text;
			} else if (errorNames.size() > 0){
				return prefixError(errorNames)+errorNames.get(0).replace("-"," ");
			}
		}
		return null;
	}

	public static String errorMessage(Element packet) {
		final Element error = packet.findChild("error");
		if (error != null && error.getChildren().size() > 0) {
			final List<String> errorNames = orderedElementNames(error.getChildren());
			final String text = error.findChildContent("text");
			if (text != null && !text.trim().isEmpty()) {
				return text;
			} else if (errorNames.size() > 0){
				return errorNames.get(0).replace("-"," ");
			}
		}
		return null;
	}

	private static String prefixError(List<String> errorNames) {
		if (errorNames.size() > 0) {
			return errorNames.get(0)+'\u001f';
		}
		return "";
	}

	private static List<String> orderedElementNames(List<Element> children) {
		List<String> names = new ArrayList<>();
		for(Element child : children) {
			final String name = child.getName();
			if (name != null && !name.equals("text")) {
				if ("urn:ietf:params:xml:ns:xmpp-stanzas".equals(child.getNamespace())) {
					names.add(name);
				} else {
					names.add(0, name);
				}
			}
		}
		return names;
	}
}
