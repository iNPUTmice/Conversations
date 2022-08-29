package eu.siacs.conversations.services;

import android.content.Intent;
import android.util.Log;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.utils.Compatibility;

public class PushMessageReceiver extends FirebaseMessagingService {

	@Override
	public void onMessageReceived(RemoteMessage message) {
		if (!EventReceiver.hasEnabledAccounts(this)) {
			Log.d(Config.LOGTAG,"PushMessageReceiver ignored message because no accounts are enabled");
			return;
		}
		final Map<String, String> data = message.getData();
		final Intent intent = new Intent(this, XmppConnectionService.class);
		intent.setAction(XmppConnectionService.ACTION_FCM_MESSAGE_RECEIVED);
		intent.putExtra("account", data.get("account"));
		Compatibility.startService(this, intent);
	}

	@Override
	public void onNewToken(String token) {
		if (!EventReceiver.hasEnabledAccounts(this)) {
			Log.d(Config.LOGTAG,"PushMessageReceiver ignored new token because no accounts are enabled");
			return;
		}
		final Intent intent = new Intent(this, XmppConnectionService.class);
		intent.setAction(XmppConnectionService.ACTION_FCM_TOKEN_REFRESH);
		Compatibility.startService(this, intent);
	}

}
