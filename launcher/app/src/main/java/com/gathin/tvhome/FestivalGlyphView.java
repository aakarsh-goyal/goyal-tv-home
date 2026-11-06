package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** A small still glyph for the festival card: a diya, a marigold, a splash of colour, a rakhi, a tree, a crescent. */
final class FestivalGlyphView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();
    private String key = "";
    private int accent = 0xFFFFB23C;

    FestivalGlyphView(Context c) {
        this(c, null);
    }

    FestivalGlyphView(Context c, AttributeSet a) {
        super(c, a);
    }

    void set(String key, int accent) {
        this.key = key;
        this.accent = accent;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float u = Math.min(getWidth(), getHeight()) / 24f;
        c.save();
        c.translate((getWidth() - 24 * u) / 2f, (getHeight() - 24 * u) / 2f);
        c.scale(u, u);
        p.setStyle(Paint.Style.FILL);
        switch (key) {
            case "holi": splash(c); break;
            case "rakhi": case "bhaidooj": bracelet(c); break;
            case "christmas": tree(c); break;
            case "shivratri": case "janmashtami": crescent(c); break;
            case "navratri": case "dussehra": case "ganesh": case "sankranti": case "baisakhi": marigold(c); break;
            default: diya(c);
        }
        c.restore();
    }

    private void diya(Canvas c) {
        p.setColor(0xFFC1622B);                              // the clay lamp
        path.reset();
        path.moveTo(3, 14);
        path.quadTo(12, 26, 21, 14);
        path.quadTo(12, 16.5f, 3, 14);
        c.drawPath(path, p);
        p.setColor(0xFFFFD66B);                              // the flame
        path.reset();
        path.moveTo(12, 3);
        path.quadTo(16.5f, 8, 12, 13.5f);
        path.quadTo(7.5f, 8, 12, 3);
        c.drawPath(path, p);
        p.setColor(0xFFFFFFFF);
        c.drawCircle(12, 10, 1.6f, p);
    }

    private void marigold(Canvas c) {
        p.setColor(accent);
        for (int i = 0; i < 8; i++) {
            c.save();
            c.rotate(i * 45f, 12, 12);
            r.set(9.2f, 2.5f, 14.8f, 11f);
            c.drawOval(r, p);
            c.restore();
        }
        p.setColor(0xFFC1622B);
        c.drawCircle(12, 12, 3.2f, p);
    }

    private void splash(Canvas c) {
        int[] col = {0xFFFF4DA6, 0xFF3DDC97, 0xFF4DA6FF, 0xFFFFD60A, 0xFFB46BFF};
        float[][] pos = {{8, 8, 5}, {16, 9, 4.2f}, {12, 15.5f, 5}, {6, 16, 3}, {18, 17, 3.4f}};
        for (int i = 0; i < 5; i++) {
            p.setColor(col[i]);
            c.drawCircle(pos[i][0], pos[i][1], pos[i][2], p);
        }
    }

    private void bracelet(Canvas c) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.2f);
        p.setColor(accent);
        c.drawCircle(12, 12, 7.5f, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFFFD66B);
        for (int i = 0; i < 6; i++) {
            double a = i * Math.PI / 3;
            c.drawCircle(12 + (float) Math.cos(a) * 7.5f, 12 + (float) Math.sin(a) * 7.5f, 1.8f, p);
        }
        p.setColor(0xFFFFFFFF);
        c.drawCircle(12, 4.5f, 2.4f, p);
    }

    private void tree(Canvas c) {
        p.setColor(0xFF2FB765);
        for (int i = 0; i < 3; i++) {
            path.reset();
            path.moveTo(12, 2.5f + i * 5);
            path.lineTo(4.5f + i * 1.2f, 11.5f + i * 4);
            path.lineTo(19.5f - i * 1.2f, 11.5f + i * 4);
            path.close();
            c.drawPath(path, p);
        }
        p.setColor(0xFF8A5A2B);
        c.drawRect(10.5f, 20, 13.5f, 23, p);
        p.setColor(0xFFFFD60A);
        c.drawCircle(12, 3, 1.6f, p);
    }

    private void crescent(Canvas c) {
        p.setColor(0xFFF4F1E6);
        path.reset();
        path.addCircle(11, 12, 8, Path.Direction.CW);
        Path cut = new Path();
        cut.addCircle(15, 9.5f, 7, Path.Direction.CW);
        path.op(cut, Path.Op.DIFFERENCE);
        c.drawPath(path, p);
        p.setColor(accent);
        c.drawCircle(19, 6, 1.5f, p);
    }
}
