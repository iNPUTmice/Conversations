/*
 * Copyright (c) 2018, Daniel Gultsch All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation and/or
 * other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package eu.siacs.conversations.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.os.Build;
import android.preference.PreferenceManager;
import android.util.TypedValue;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.google.android.material.snackbar.Snackbar;

import eu.siacs.conversations.R;
import eu.siacs.conversations.ui.SettingsActivity;

public class ThemeHelper {

	public static int find(final Context context) {
		final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
		final Resources resources = context.getResources();
		final String themeId = getThemeId(sharedPreferences, resources);
		final String fontSize = sharedPreferences.getString("font_size", resources.getString(R.string.default_font_size));
		switch (fontSize) {
			case "medium":
				switch (themeId) {
					case "dark":
						return R.style.ConversationsTheme_Dark_Medium;
					case "black":
						return R.style.ConversationsTheme_Black_Medium;
					case "darkPurple":
						return R.style.ConversationsTheme_DarkPurple_Medium;
					case "blackPurple":
						return R.style.ConversationsTheme_BlackPurple_Medium;
					case "darkBordeaux":
						return R.style.ConversationsTheme_DarkBordeaux_Medium;
					case "blackBordeaux":
						return R.style.ConversationsTheme_BlackBordeaux_Medium;
					case "orange":
						return R.style.ConversationsTheme_Orange_Medium;
					case "darkOrange":
						return R.style.ConversationsTheme_DarkOrange_Medium;
					case "blackOrange":
						return R.style.ConversationsTheme_BlackOrange_Medium;
					default:
						return R.style.ConversationsTheme_Medium;
				}
			case "large":
				switch (themeId) {
					case "dark":
						return R.style.ConversationsTheme_Dark_Large;
					case "black":
						return R.style.ConversationsTheme_Black_Large;
					case "darkPurple":
						return R.style.ConversationsTheme_DarkPurple_Large;
					case "blackPurple":
						return R.style.ConversationsTheme_BlackPurple_Large;
					case "darkBordeaux":
						return R.style.ConversationsTheme_DarkBordeaux_Large;
					case "blackBordeaux":
						return R.style.ConversationsTheme_BlackBordeaux_Large;
					case "orange":
						return R.style.ConversationsTheme_Orange_Large;
					case "darkOrange":
						return R.style.ConversationsTheme_DarkOrange_Large;
					case "blackOrange":
						return R.style.ConversationsTheme_BlackOrange_Large;
					default:
						return R.style.ConversationsTheme_Large;
				}
			default:
				switch (themeId) {
					case "dark":
						return R.style.ConversationsTheme_Dark;
					case "black":
						return R.style.ConversationsTheme_Black;
					case "darkPurple":
						return R.style.ConversationsTheme_DarkPurple;
					case "blackPurple":
						return R.style.ConversationsTheme_BlackPurple;
					case "darkBordeaux":
						return R.style.ConversationsTheme_DarkBordeaux;
					case "blackBordeaux":
						return R.style.ConversationsTheme_BlackBordeaux;
					case "orange":
						return R.style.ConversationsTheme_Orange;
					case "darkOrange":
						return R.style.ConversationsTheme_DarkOrange;
					case "blackOrange":
						return R.style.ConversationsTheme_BlackOrange;
					default:
						return R.style.ConversationsTheme;
				}
		}
	}

	public static int findDialog(Context context) {
		final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
		final Resources resources = context.getResources();
		final String themeId = getThemeId(sharedPreferences, resources);
		final String fontSize = sharedPreferences.getString("font_size", resources.getString(R.string.default_font_size));
		switch (fontSize) {
			case "medium":
				switch (themeId) {
					case "dark":
						return R.style.ConversationsTheme_Dark_Dialog_Medium;
					case "black":
						return R.style.ConversationsTheme_Dark_Dialog_Medium;
					case "darkPurple":
						return R.style.ConversationsTheme_DarkPurple_Dialog_Medium;
					case "blackPurple":
						return R.style.ConversationsTheme_DarkPurple_Dialog_Medium;
					case "darkBordeaux":
						return R.style.ConversationsTheme_DarkBordeaux_Dialog_Medium;
					case "blackBordeaux":
						return R.style.ConversationsTheme_DarkBordeaux_Dialog_Medium;
					case "darkOrange":
						return R.style.ConversationsTheme_DarkOrange_Dialog_Medium;
					case "blackOrange":
						return R.style.ConversationsTheme_DarkOrange_Dialog_Medium;
					default:
						return R.style.ConversationsTheme_Dialog_Medium;
				}
			case "large":
				switch (themeId) {
					case "dark":
						return R.style.ConversationsTheme_Dark_Dialog_Large;
					case "black":
						return R.style.ConversationsTheme_Dark_Dialog_Large;
					case "darkPurple":
						return R.style.ConversationsTheme_DarkPurple_Dialog_Large;
					case "blackPurple":
						return R.style.ConversationsTheme_DarkPurple_Dialog_Large;
					case "darkBordeaux":
						return R.style.ConversationsTheme_DarkBordeaux_Dialog_Large;
					case "blackBordeaux":
						return R.style.ConversationsTheme_DarkBordeaux_Dialog_Large;
					case "darkOrange":
						return R.style.ConversationsTheme_DarkOrange_Dialog_Large;
					case "blackOrange":
						return R.style.ConversationsTheme_DarkOrange_Dialog_Large;
					default:
						return R.style.ConversationsTheme_Dialog_Large;
				}
			default:
				switch (themeId) {
					case "dark":
						return R.style.ConversationsTheme_Dark_Dialog;
					case "black":
						return R.style.ConversationsTheme_Dark_Dialog;
					case "darkPurple":
						return R.style.ConversationsTheme_DarkPurple_Dialog;
					case "blackPurple":
						return R.style.ConversationsTheme_DarkPurple_Dialog;
					case "darkBordeaux":
						return R.style.ConversationsTheme_DarkBordeaux_Dialog;
					case "blackBordeaux":
						return R.style.ConversationsTheme_DarkBordeaux_Dialog;
					case "darkOrange":
						return R.style.ConversationsTheme_DarkOrange_Dialog;
					case "blackOrange":
						return R.style.ConversationsTheme_DarkOrange_Dialog;
					default:
						return R.style.ConversationsTheme_Dialog;
				}
		}
	}

	public static void fix(Snackbar snackbar) {
		final Context context = snackbar.getContext();
		TypedArray typedArray = context.obtainStyledAttributes(new int[]{R.attr.TextSizeBody1});
		final float size = typedArray.getDimension(0,0f);
		typedArray.recycle();
		if (size != 0f) {
			final TextView text = snackbar.getView().findViewById(com.google.android.material.R.id.snackbar_text);
			final TextView action = snackbar.getView().findViewById(com.google.android.material.R.id.snackbar_action);
			if (text != null && action != null) {
				text.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
				action.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
				action.setTextColor(ContextCompat.getColor(context, R.color.blue_a100));
			}
		}
	}

	private static String getThemeId(final SharedPreferences sharedPreferences, final Resources resources) {
		String themeId = sharedPreferences.getString(SettingsActivity.THEME, resources.getString(R.string.theme));
		if (themeId.equals("automatic")){
			themeId = getAutoTheme(resources);
		}
		return themeId;
	}

	private static String getAutoTheme(final Resources resources){
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q){
			if ((resources.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES){
				return "dark";
			}
		}
		return "light";
	}
}
