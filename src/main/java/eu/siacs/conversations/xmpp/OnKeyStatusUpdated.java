package eu.siacs.conversations.xmpp;

import eu.siacs.conversations.crypto.axolotl.AxolotlService;

public interface OnKeyStatusUpdated {
	void onKeyStatusUpdated(AxolotlService.FetchStatus report);
}
