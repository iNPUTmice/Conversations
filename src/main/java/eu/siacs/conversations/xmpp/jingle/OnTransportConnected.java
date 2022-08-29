package eu.siacs.conversations.xmpp.jingle;

public interface OnTransportConnected {
	void failed();

	void established();
}
