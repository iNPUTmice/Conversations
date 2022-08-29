package eu.siacs.conversations.utils;

import android.graphics.Color;

import org.hsluv.HUSLColorConverter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

class XEP0392Helper {

    private static double angle(String nickname) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest(nickname.getBytes(StandardCharsets.UTF_8));
            int angle = ((int) (digest[0]) & 0xff) + ((int) (digest[1]) & 0xff) * 256;
            return angle / 65536.;
        } catch (Exception e) {
            return 0.0;
        }
    }

    static int rgbFromNick(String name) {
        double[] hsluv = new double[3];
        hsluv[0] = angle(name) * 360;
        hsluv[1] = 100;
        hsluv[2] = 50;
        double[] rgb = HUSLColorConverter.hsluvToRgb(hsluv);
        return Color.rgb((int) Math.round(rgb[0] * 255), (int) Math.round(rgb[1] * 255), (int) Math.round(rgb[2] * 255));
    }
}
