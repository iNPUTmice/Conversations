package eu.siacs.conversations.xmpp;

import eu.siacs.conversations.entities.Account;

public interface OnStatusChanged {
	void onStatusChanged(Account account);
}
