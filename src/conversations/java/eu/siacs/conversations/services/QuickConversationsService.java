package eu.siacs.conversations.services;

import android.content.Intent;
import android.util.Log;

import eu.siacs.conversations.Config;

public class QuickConversationsService extends AbstractQuickConversationsService {

    QuickConversationsService(XmppConnectionService xmppConnectionService) {
        super(xmppConnectionService);
    }

    @Override
    public void considerSync() {

    }

    @Override
    public void signalAccountStateChange() {

    }

    @Override
    public boolean isSynchronizing() {
        return false;
    }

    @Override
    public void considerSyncBackground(boolean force) {

    }

    @Override
    public void handleSmsReceived(Intent intent) {
        Log.d(Config.LOGTAG,"ignoring received SMS");
    }
}