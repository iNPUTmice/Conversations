package eu.siacs.conversations.ui.util;

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
    /*public static int getQuoteColors(XmppActivity activity, boolean darkBackground, int quoteDepth){
        int[] colorsLight = R.style.ConversationsTheme_Dark;
        int[] colorsDark = Config.QUOTE_COLOR_ARRAY_DARK;

       Collections.rotate(Collections.singletonList(colorsLight), quoteDepth);
       Collections.rotate(Collections.singletonList(colorsDark), quoteDepth);

       Arrays.stream(colorsLight).toArray();

        int quoteColors =  darkBackground ? ContextCompat.getColor(activity, colorsLight[quoteDepth-1])
                : ContextCompat.getColor(activity, colorsDark[quoteDepth-1]);

        Collections.rotate

        return quoteColors;
    };*/
}
