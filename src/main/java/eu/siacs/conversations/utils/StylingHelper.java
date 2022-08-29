/*
 * Copyright (c) 2017, Daniel Gultsch All rights reserved.
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
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.ParcelableSpan;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.ui.text.QuoteSpan;

public class StylingHelper {

	private static final List<? extends Class<? extends ParcelableSpan>> SPAN_CLASSES = Arrays.asList(
			StyleSpan.class,
			StrikethroughSpan.class,
			TypefaceSpan.class,
			ForegroundColorSpan.class
	);

	public static void clear(final Editable editable) {
		final int end = editable.length() - 1;
		for (Class<? extends ParcelableSpan> clazz : SPAN_CLASSES) {
			for (ParcelableSpan span : editable.getSpans(0, end, clazz)) {
				editable.removeSpan(span);
			}
		}
	}

	public static void format(final Editable editable, int start, int end, @ColorInt int textColor) {
		for (ImStyleParser.Style style : ImStyleParser.parse(editable, start, end)) {
			final int keywordLength = style.getKeyword().length();
			editable.setSpan(createSpanForStyle(style), style.getStart() + keywordLength, style.getEnd() - keywordLength + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
			makeKeywordOpaque(editable, style.getStart(), style.getStart() + keywordLength, textColor);
			makeKeywordOpaque(editable, style.getEnd() - keywordLength + 1, style.getEnd() + 1, textColor);
		}
	}

	public static void format(final Editable editable, @ColorInt int textColor) {
		int end = 0;
		Message.MergeSeparator[] spans = editable.getSpans(0, editable.length() - 1, Message.MergeSeparator.class);
		for (Message.MergeSeparator span : spans) {
			format(editable, end, editable.getSpanStart(span), textColor);
			end = editable.getSpanEnd(span);
		}
		format(editable, end, editable.length() - 1, textColor);
	}

	public static void highlight(final Context context, final Editable editable, List<String> needles, boolean dark) {
		for (String needle : needles) {
			if (!FtsUtils.isKeyword(needle)) {
				highlight(context, editable, needle, dark);
			}
		}
	}

	public static List<String> filterHighlightedWords(List<String> terms) {
		List<String> words = new ArrayList<>();
		for (String term : terms) {
			if (!FtsUtils.isKeyword(term)) {
				StringBuilder builder = new StringBuilder();
				for (int codepoint, i = 0; i < term.length(); i += Character.charCount(codepoint)) {
					codepoint = term.codePointAt(i);
					if (Character.isLetterOrDigit(codepoint)) {
						builder.append(Character.toChars(codepoint));
					} else if (builder.length() > 0) {
						words.add(builder.toString());
						builder.delete(0, builder.length());
					}
				}
				if (builder.length() > 0) {
					words.add(builder.toString());
				}
			}
		}
		return words;
	}

	private static void highlight(final Context context, final Editable editable, String needle, boolean dark) {
		final int length = needle.length();
		String string = editable.toString();
		int start = indexOfIgnoreCase(string, needle, 0);
		while (start != -1) {
			int end = start + length;
			editable.setSpan(new BackgroundColorSpan(ContextCompat.getColor(context, dark ? R.color.blue_a100 : R.color.blue_a400)), start, end, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
			editable.setSpan(new ForegroundColorSpan(ContextCompat.getColor(context, dark ? R.color.black87 : R.color.white)), start, end, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
			start = indexOfIgnoreCase(string, needle, start + length);
		}

	}

	static CharSequence subSequence(CharSequence charSequence, int start, int end) {
		if (start == 0 && charSequence.length() + 1 == end) {
			return charSequence;
		}
		if (charSequence instanceof Spannable) {
			Spannable spannable = (Spannable) charSequence;
			Spannable sub = (Spannable) spannable.subSequence(start, end);
			for (Class<? extends ParcelableSpan> clazz : SPAN_CLASSES) {
				ParcelableSpan[] spannables = spannable.getSpans(start, end, clazz);
				for (ParcelableSpan parcelableSpan : spannables) {
					int beginSpan = spannable.getSpanStart(parcelableSpan);
					int endSpan = spannable.getSpanEnd(parcelableSpan);
					if (beginSpan >= start && endSpan <= end) {
						continue;
					}
					sub.setSpan(clone(parcelableSpan), Math.max(beginSpan - start, 0), Math.min(sub.length() - 1, endSpan), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
				}
			}
			return sub;
		} else {
			return charSequence.subSequence(start, end);
		}
	}

	private static ParcelableSpan clone(ParcelableSpan span) {
		if (span instanceof ForegroundColorSpan) {
			return new ForegroundColorSpan(((ForegroundColorSpan) span).getForegroundColor());
		} else if (span instanceof TypefaceSpan) {
			return new TypefaceSpan(((TypefaceSpan) span).getFamily());
		} else if (span instanceof StyleSpan) {
			return new StyleSpan(((StyleSpan) span).getStyle());
		} else if (span instanceof StrikethroughSpan) {
			return new StrikethroughSpan();
		} else {
			throw new AssertionError("Unknown Span");
		}
	}

	public static boolean isDarkText(TextView textView) {
		int argb = textView.getCurrentTextColor();
		return Color.red(argb) + Color.green(argb) + Color.blue(argb) == 0;
	}

	private static ParcelableSpan createSpanForStyle(ImStyleParser.Style style) {
		switch (style.getKeyword()) {
			case "*":
				return new StyleSpan(Typeface.BOLD);
			case "_":
				return new StyleSpan(Typeface.ITALIC);
			case "~":
				return new StrikethroughSpan();
			case "`":
			case "```":
				return new TypefaceSpan("monospace");
			default:
				throw new AssertionError("Unknown Style");
		}
	}

	private static void makeKeywordOpaque(final Editable editable, int start, int end, @ColorInt int fallbackTextColor) {
		QuoteSpan[] quoteSpans = editable.getSpans(start, end, QuoteSpan.class);
		@ColorInt int textColor = quoteSpans.length > 0 ? quoteSpans[0].getColor() : fallbackTextColor;
		@ColorInt int keywordColor = transformColor(textColor);
		editable.setSpan(new ForegroundColorSpan(keywordColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
	}

	private static
	@ColorInt
	int transformColor(@ColorInt int c) {
		return Color.argb(Math.round(Color.alpha(c) * 0.45f), Color.red(c), Color.green(c), Color.blue(c));
	}

	private static int indexOfIgnoreCase(final String haystack, final String needle, final int start) {
		if (haystack == null || needle == null) {
			return -1;
		}
		final int endLimit = haystack.length() - needle.length() + 1;
		if (start > endLimit) {
			return -1;
		}
		if (needle.length() == 0) {
			return start;
		}
		for (int i = start; i < endLimit; i++) {
			if (haystack.regionMatches(true, i, needle, 0, needle.length())) {
				return i;
			}
		}
		return -1;
	}

	public static class MessageEditorStyler implements TextWatcher {

		private final EditText mEditText;

		public MessageEditorStyler(EditText editText) {
			this.mEditText = editText;
		}

		@Override
		public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

		}

		@Override
		public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {

		}

		@Override
		public void afterTextChanged(Editable editable) {
			clear(editable);
			format(editable, mEditText.getCurrentTextColor());
		}
	}
}
