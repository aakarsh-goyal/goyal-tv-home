package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Four rising bars, filled up to the signal level (0 to 4), the way a phone's status bar draws Wi-Fi strength. */
final class WifiBarsView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float d;
    private int level;

    WifiBarsView(Context c) {
        this(c, null);
    }

    WifiBarsView(Context c, android.util.AttributeSet a) {
        super(c, a);
        d = c.getResources().getDisplayMetrics().density;
    }

    void setLevel(int level) {
        level = Math.max(0, Math.min(4, level));
        if (level == this.level) return;
        this.level = level;
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(Math.round(26 * d), Math.round(20 * d));
    }

    @Override
    protected void onDraw(Canvas c) {
        float bw = 4.5f * d, gap = 2.6f * d, base = getHeight();
        for (int i = 0; i < 4; i++) {
            float bh = (6f + i * 4.6f) * d;
            float x = i * (bw + gap);
            p.setColor(i < level ? 0xFFFFFFFF : 0x40FFFFFF);
            r.set(x, base - bh, x + bw, base);
            c.drawRoundRect(r, 1.6f * d, 1.6f * d, p);
        }
    }
}
