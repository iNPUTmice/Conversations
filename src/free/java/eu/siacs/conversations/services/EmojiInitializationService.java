package eu.siacs.conversations.services;

import android.content.Context;

import androidx.emoji2.bundled.BundledEmojiCompatConfig;
import androidx.emoji2.text.EmojiCompat;

public class EmojiInitializationService {

    public static void execute(final Context context) {
        EmojiCompat.init(new BundledEmojiCompatConfig(context).setReplaceAll(true));
    }

}
