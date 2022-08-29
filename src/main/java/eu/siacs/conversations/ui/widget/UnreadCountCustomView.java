package eu.siacs.conversations.ui.widget;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

import eu.siacs.conversations.R;

public class UnreadCountCustomView extends View {

    private int unreadCount;
    private Paint paint, textPaint;
    private int backgroundColor = 0xff326130;

    public UnreadCountCustomView(Context context) {
        super(context);
        init();
    }

    public UnreadCountCustomView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initXMLAttrs(context, attrs);
        init();
    }

    public UnreadCountCustomView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initXMLAttrs(context, attrs);
        init();
    }

    private void initXMLAttrs(Context context, AttributeSet attrs) {
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.UnreadCountCustomView);
        setBackgroundColor(a.getColor(a.getIndex(0), ContextCompat.getColor(context, R.color.green700_desaturated)));
        a.recycle();
    }

    void init() {
        paint = new Paint();
        paint.setColor(backgroundColor);
        paint.setAntiAlias(true);
        textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setAntiAlias(true);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float midx = canvas.getWidth() / 2.0f;
        float midy = canvas.getHeight() / 2.0f;
        float radius = Math.min(canvas.getWidth(), canvas.getHeight()) / 2.0f;
        float textOffset = canvas.getWidth() / 6.0f;
        textPaint.setTextSize(0.95f * radius);
        canvas.drawCircle(midx, midy, radius * 0.94f, paint);
        canvas.drawText(unreadCount > 999 ? "\u221E" : String.valueOf(unreadCount), midx, midy + textOffset, textPaint);

    }

    public void setUnreadCount(int unreadCount) {
        this.unreadCount = unreadCount;
        invalidate();
    }

    public void setBackgroundColor(int backgroundColor) {
        this.backgroundColor = backgroundColor;
    }
}