package eu.siacs.conversations.ui.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Rational;

import eu.siacs.conversations.Config;

public class SurfaceViewRenderer extends org.webrtc.SurfaceViewRenderer {

    private Rational aspectRatio = new Rational(1,1);

    private OnAspectRatioChanged onAspectRatioChanged;

    public SurfaceViewRenderer(Context context) {
        super(context);
    }

    public SurfaceViewRenderer(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void onFrameResolutionChanged(int videoWidth, int videoHeight, int rotation) {
        super.onFrameResolutionChanged(videoWidth, videoHeight, rotation);
        final int rotatedWidth = rotation != 0 && rotation != 180 ? videoHeight : videoWidth;
        final int rotatedHeight = rotation != 0 && rotation != 180 ? videoWidth : videoHeight;
        final Rational currentRational = this.aspectRatio;
        this.aspectRatio = new Rational(rotatedWidth, rotatedHeight);
        Log.d(Config.LOGTAG,"onFrameResolutionChanged("+rotatedWidth+","+rotatedHeight+","+aspectRatio+")");
        if (currentRational.equals(this.aspectRatio) || onAspectRatioChanged == null) {
            return;
        }
        onAspectRatioChanged.onAspectRatioChanged(this.aspectRatio);
    }

    public void setOnAspectRatioChanged(final OnAspectRatioChanged onAspectRatioChanged) {
        this.onAspectRatioChanged = onAspectRatioChanged;
    }

    public Rational getAspectRatio() {
        return this.aspectRatio;
    }

    public interface OnAspectRatioChanged {
        void onAspectRatioChanged(final Rational rational);
    }
}
