package eu.siacs.conversations.ui.util;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.utils.UIHelper;

public class QuoteHelper {


    public static final char QUOTE_CHAR = '>';
    public static final char QUOTE_END_CHAR = '<'; // used for one check, not for actual quoting
    public static final char QUOTE_ALT_CHAR = '»';
    public static final char QUOTE_ALT_END_CHAR = '«';

    public static boolean isPositionQuoteCharacter(CharSequence body, int pos) {
        // second part of logical check actually goes against the logic indicated in the method name, since it also checks for context
        // but it's very useful
        return body.charAt(pos) == QUOTE_CHAR || isPositionAltQuoteStart(body, pos);
    }

    public static boolean isPositionQuoteEndCharacter(CharSequence body, int pos) {
        return body.charAt(pos) == QUOTE_END_CHAR;
    }

    public static boolean isPositionAltQuoteCharacter(CharSequence body, int pos) {
        return body.charAt(pos) == QUOTE_ALT_CHAR;
    }

    public static boolean isPositionAltQuoteEndCharacter(CharSequence body, int pos) {
        return body.charAt(pos) == QUOTE_ALT_END_CHAR;
    }

    public static boolean isPositionAltQuoteStart(CharSequence body, int pos) {
        return isPositionAltQuoteCharacter(body, pos)
                && isPositionPrecededByPreQuote(body, pos)
                && !isPositionFollowedByAltQuoteEnd(body, pos);
    }

    public static boolean isPositionFollowedByQuoteChar(CharSequence body, int pos) {
        return body.length() > pos + 1 && isPositionQuoteCharacter(body, pos + 1);
    }

    /**
     *  'Prequote' means anything we require or can accept in front of a QuoteChar.
     */
    public static boolean isPositionPrecededByPreQuote(CharSequence body, int pos) {
        return UIHelper.isPositionPrecededByLineStart(body, pos);
    }

    public static boolean isPositionQuoteStart(CharSequence body, int pos) {
        return (isPositionQuoteCharacter(body, pos)
                && isPositionPrecededByPreQuote(body, pos)
                && (UIHelper.isPositionFollowedByQuoteableCharacter(body, pos)
                || isPositionFollowedByQuoteChar(body, pos)));
    }

    public static boolean bodyContainsQuoteStart(CharSequence body) {
        for (int i = 0; i < body.length(); i++) {
            if (isPositionQuoteStart(body, i)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isPositionFollowedByAltQuoteEnd(CharSequence body, int pos) {
        if (body.length() <= pos + 1 || Character.isWhitespace(body.charAt(pos + 1))) {
            return false;
        }
        boolean previousWasWhitespace = false;
        for (int i = pos + 1; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\n' || isPositionAltQuoteCharacter(body, i)) {
                return false;
            } else if (isPositionAltQuoteEndCharacter(body, i) && !previousWasWhitespace) {
                return true;
            } else {
                previousWasWhitespace = Character.isWhitespace(c);
            }
        }
        return false;
    }

    public static boolean isNestedTooDeeply(CharSequence line) {
        if (isPositionQuoteStart(line, 0)) {
            int nestingDepth = 1;
            for (int i = 1; i < line.length(); i++) {
                if (isPositionQuoteStart(line, i)) {
                    nestingDepth++;
                }
                if (nestingDepth > (Config.QUOTING_MAX_DEPTH - 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static String replaceAltQuoteCharsInText(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (isPositionAltQuoteStart(text, i)) {
                text = text.substring(0, i) + QUOTE_CHAR + text.substring(i + 1);
            }
        }
        return text;
    }
}