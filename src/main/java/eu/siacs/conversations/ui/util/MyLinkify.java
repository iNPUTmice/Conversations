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

package eu.siacs.conversations.ui.util;

import android.os.Build;
import android.text.Editable;
import android.text.util.Linkify;

import java.util.Locale;

import eu.siacs.conversations.ui.text.FixedURLSpan;
import eu.siacs.conversations.utils.GeoHelper;
import eu.siacs.conversations.utils.Patterns;
import eu.siacs.conversations.utils.XmppUri;

public class MyLinkify {

    private static final Linkify.TransformFilter WEBURL_TRANSFORM_FILTER = (matcher, url) -> {
        if (url == null) {
            return null;
        }
        final String lcUrl = url.toLowerCase(Locale.US);
        if (lcUrl.startsWith("http://") || lcUrl.startsWith("https://")) {
            return removeTrailingBracket(url);
        } else {
            return "http://" + removeTrailingBracket(url);
        }
    };

    private static String removeTrailingBracket(final String url) {
        int numOpenBrackets = 0;
        for (char c : url.toCharArray()) {
            if (c == '(') {
                ++numOpenBrackets;
            } else if (c == ')') {
                --numOpenBrackets;
            }
        }
        if (numOpenBrackets != 0 && url.charAt(url.length() - 1) == ')') {
            return url.substring(0, url.length() - 1);
        } else {
            return url;
        }
    }

    private static final Linkify.MatchFilter WEBURL_MATCH_FILTER = (cs, start, end) -> {
        if (start > 0) {
            if (cs.charAt(start - 1) == '@' || cs.charAt(start - 1) == '.'
                    || cs.subSequence(Math.max(0, start - 3), start).equals("://")) {
                return false;
            }
        }

        if (end < cs.length()) {
            // Reject strings that were probably matched only because they contain a dot followed by
            // by some known TLD (see also comment for WORD_BOUNDARY in Patterns.java)
            return !isAlphabetic(cs.charAt(end - 1)) || !isAlphabetic(cs.charAt(end));
        }

        return true;
    };

    private static final Linkify.MatchFilter XMPPURI_MATCH_FILTER = (s, start, end) -> {
        XmppUri uri = new XmppUri(s.subSequence(start, end).toString());
        return uri.isValidJid();
    };

    private static boolean isAlphabetic(final int code) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            return Character.isAlphabetic(code);
        }

        switch (Character.getType(code)) {
            case Character.UPPERCASE_LETTER:
            case Character.LOWERCASE_LETTER:
            case Character.TITLECASE_LETTER:
            case Character.MODIFIER_LETTER:
            case Character.OTHER_LETTER:
            case Character.LETTER_NUMBER:
                return true;
            default:
                return false;
        }
    }

    public static void addLinks(Editable body, boolean includeGeo) {
        Linkify.addLinks(body, Patterns.XMPP_PATTERN, "xmpp", XMPPURI_MATCH_FILTER, null);
        Linkify.addLinks(body, Patterns.AUTOLINK_WEB_URL, "http", WEBURL_MATCH_FILTER, WEBURL_TRANSFORM_FILTER);
        if (includeGeo) {
            Linkify.addLinks(body, GeoHelper.GEO_URI, "geo");
        }
        FixedURLSpan.fix(body);
    }
}
