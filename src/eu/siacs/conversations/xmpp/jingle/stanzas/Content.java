package eu.siacs.conversations.xmpp.jingle.stanzas;

import java.util.ArrayList;
import java.util.List;

import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xmpp.jingle.JingleFile;

public class Content extends Element {
	private Content(String name) {
		super(name);
	}
	
	public Content() {
		super("content");
	}

	public void setFileOffer(JingleFile actualFile) {
		Element description = this.addChild("description", "urn:xmpp:jingle:apps:file-transfer:3");
		Element offer = description.addChild("offer");
		Element file = offer.addChild("file");
		file.addChild("size").setContent(""+actualFile.getSize());
		file.addChild("name").setContent(actualFile.getName());
	}
	
	public Element getFileOffer() {
		Element description = this.findChild("description", "urn:xmpp:jingle:apps:file-transfer:3");
		if (description==null) {
			return null;
		}
		Element offer = description.findChild("offer");
		if (offer==null) {
			return null;
		}
		return offer.findChild("file");
	}

	public void setCandidates(String transportId, List<Element> canditates) {
		Element transport = this.findChild("transport", "urn:xmpp:jingle:transports:s5b:1");
		if (transport==null) {
			transport = this.addChild("transport", "urn:xmpp:jingle:transports:s5b:1");
		}
		transport.setAttribute("sid", transportId);
		transport.clearChildren();
		for(Element canditate : canditates) {
			transport.addChild(canditate);
		}
	}
	
	public List<Element> getCanditates() {
		Element transport = this.findChild("transport", "urn:xmpp:jingle:transports:s5b:1");
		if (transport==null) {
			return new ArrayList<Element>();
		} else {
			return transport.getChildren();
		}
	}
	
	public String getTransportId() {
		Element transport = this.findChild("transport", "urn:xmpp:jingle:transports:s5b:1");
		if (transport==null) {
			return null;
		}
		return transport.getAttribute("sid");
	}
	
	public String getUsedCandidate() {
		Element transport = this.findChild("transport", "urn:xmpp:jingle:transports:s5b:1");
		if (transport==null) {
			return null;
		}
		Element usedCandidate = transport.findChild("candidate-used");
		if (usedCandidate==null) {
			return null;
		} else {
			return usedCandidate.getAttribute("cid");
		}
	}
	
	public boolean hasCandidateError() {
		Element transport = this.findChild("transport", "urn:xmpp:jingle:transports:s5b:1");
		if (transport==null) {
			return false;
		}
		return transport.hasChild("candidate-error");
	}
	
	public void setUsedCandidate(String transportId, String cid) {
		Element transport = this.findChild("transport", "urn:xmpp:jingle:transports:s5b:1");
		if (transport==null) {
			transport = this.addChild("transport", "urn:xmpp:jingle:transports:s5b:1");
		}
		transport.setAttribute("sid", transportId);
		transport.clearChildren();
		Element usedCandidate = transport.addChild("candidate-used");
		usedCandidate.setAttribute("cid",cid);
	}

	public void addCandidate(Element candidate) {
		Element transport = this.findChild("transport", "urn:xmpp:jingle:transports:s5b:1");
		if (transport==null) {
			transport = this.addChild("transport", "urn:xmpp:jingle:transports:s5b:1");
		}
		transport.addChild(candidate);
	}

	public void setFileOffer(Element fileOffer) {
		Element description = this.findChild("description", "urn:xmpp:jingle:apps:file-transfer:3");
		if (description==null) {
			description = this.addChild("description", "urn:xmpp:jingle:apps:file-transfer:3");
		}
		description.addChild(fileOffer);
	}

	public void setCandidateError(String transportId) {
		Element transport = this.findChild("transport", "urn:xmpp:jingle:transports:s5b:1");
		if (transport==null) {
			transport = this.addChild("transport", "urn:xmpp:jingle:transports:s5b:1");
		}
		transport.setAttribute("sid", transportId);
		transport.clearChildren();
		transport.addChild("candidate-error");
	}
}
