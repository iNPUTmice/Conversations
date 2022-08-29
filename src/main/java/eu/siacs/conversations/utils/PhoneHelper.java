package eu.siacs.conversations.utils;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.ContactsContract.Profile;
import android.provider.Settings;

public class PhoneHelper {

	@SuppressLint("HardwareIds")
	public static String getAndroidId(Context context) {
		return Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
	}

	public static Uri getProfilePictureUri(Context context) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
			return null;
		}
		final String[] projection = new String[]{Profile._ID, Profile.PHOTO_URI};
		final Cursor cursor;
		try {
			cursor = context.getContentResolver().query(Profile.CONTENT_URI, projection, null, null, null);
		} catch (Throwable e) {
			return null;
		}
		if (cursor == null) {
			return null;
		}
		final String uri = cursor.moveToFirst() ? cursor.getString(1) : null;
		cursor.close();
		return uri == null ? null : Uri.parse(uri);
	}
}
