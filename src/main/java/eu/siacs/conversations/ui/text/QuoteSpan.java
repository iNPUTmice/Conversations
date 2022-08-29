package eu.siacs.conversations.ui.text;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Layout;
import android.text.TextPaint;
import android.text.style.CharacterStyle;
import android.text.style.LeadingMarginSpan;
import android.util.DisplayMetrics;
import android.util.TypedValue;

import androidx.annotation.ColorInt;

public class QuoteSpan extends CharacterStyle implements LeadingMarginSpan {

	private final int color;

	private final int width;
	private final int paddingLeft;
	private final int paddingRight;

	private static final float WIDTH_SP = 2f;
	private static final float PADDING_LEFT_SP = 1.5f;
	private static final float PADDING_RIGHT_SP = 8f;

	public QuoteSpan(int color, DisplayMetrics metrics) {
		this.color = color;
		this.width = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, WIDTH_SP, metrics);
		this.paddingLeft = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, PADDING_LEFT_SP, metrics);
		this.paddingRight = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, PADDING_RIGHT_SP, metrics);
	}

	@Override
	public void updateDrawState(TextPaint tp) {
		tp.setColor(this.color);
	}

	@Override
	public int getLeadingMargin(boolean first) {
		return paddingLeft + width + paddingRight;
	}

	@Override
	public void drawLeadingMargin(Canvas c, Paint p, int x, int dir, int top, int baseline, int bottom,
			CharSequence text, int start, int end, boolean first, Layout layout) {
		Paint.Style style = p.getStyle();
		int color = p.getColor();
		p.setStyle(Paint.Style.FILL);
		p.setColor(this.color);
		c.drawRect(x + dir * paddingLeft, top, x + dir * (paddingLeft + width), bottom, p);
		p.setStyle(style);
		p.setColor(color);
	}

	@ColorInt
	public int getColor() {
		return this.color;
	}
}