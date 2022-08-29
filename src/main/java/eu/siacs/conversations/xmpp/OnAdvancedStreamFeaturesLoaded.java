package eu.siacs.conversations.xmpp;

import eu.siacs.conversations.entities.Account;

public interface OnAdvancedStreamFeaturesLoaded {
	void onAdvancedStreamFeaturesAvailable(final Account account);
}
