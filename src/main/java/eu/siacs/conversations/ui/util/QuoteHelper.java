package eu.siacs.conversations.ui.util;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.utils.UIHelper;

public class QuoteHelper {

    public static boolean isPositionQuoteCharacter(CharSequence body, int pos){
        return body.charAt(pos) == '>';
    }

    public static boolean isPositionFollowedByQuoteChar(CharSequence body, int pos) {
        return body.length() > pos + 1 && isPositionQuoteCharacter(body, pos +1 );
    }

    // 'Prequote' means anything we require or can accept in front of a QuoteChar
    public static boolean isPositionPrecededByPrequote(CharSequence body, int pos){
        return UIHelper.isPositionPrecededByLineStart(body, pos);
    }

    public static boolean isPositionQuoteStart (CharSequence body, int pos){
        return isPositionQuoteCharacter(body, pos)
                && isPositionPrecededByPrequote(body, pos)
                && (UIHelper.isPositionFollowedByWhitespace(body, pos)
                    || isPositionFollowedByQuoteChar(body, pos));
    }

    public static boolean bodyContainsQuoteStart (CharSequence body){
       for (int i = 0; i < body.length(); i++){
            if (isPositionQuoteStart(body, i)){
                return true;
            }
        }
        return false;
    }

    public static boolean isNestedTooDeeply (CharSequence line){
        if (isPositionQuoteCharacter(line, 0)) {
            int nestingDepth = 1;
            for (int i = 1; i < line.length(); i++) {
                if (isPositionQuoteCharacter(line, i)) {
                    nestingDepth++;
                }
                if (nestingDepth > (Config.QUOTING_MAX_DEPTH - 1)) {
                    return true;
                }
            }
        }
        return false;
    }
}
