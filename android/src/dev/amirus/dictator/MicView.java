package dev.amirus.dictator;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Round mic button: teal when idle, red while listening. */
final class MicView extends View {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final int idleColor, liveColor;
    private boolean live;

    MicView(Context context, int idleColor, int liveColor) {
        super(context);
        this.idleColor = idleColor;
        this.liveColor = liveColor;
        glyph.setColor(0xFFFFFFFF);
        stroke.setColor(0xFFFFFFFF);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        setClickable(true);
        setContentDescription("Start dictation");
    }

    void setLive(boolean value) {
        live = value;
        setContentDescription(value ? "Stop dictation" : "Start dictation");
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float size = Math.min(getWidth(), getHeight()), u = size / 100f;
        float cx = getWidth() / 2f, top = (getHeight() - size) / 2f;
        fill.setColor(live ? liveColor : idleColor);
        c.drawCircle(cx, getHeight() / 2f, size / 2f, fill);
        if (isPressed()) {
            fill.setColor(0x22000000);
            c.drawCircle(cx, getHeight() / 2f, size / 2f, fill);
        }
        box.set(cx - 9 * u, top + 24 * u, cx + 9 * u, top + 58 * u);
        c.drawRoundRect(box, 9 * u, 9 * u, glyph);
        stroke.setStrokeWidth(4.5f * u);
        box.set(cx - 17 * u, top + 30 * u, cx + 17 * u, top + 66 * u);
        c.drawArc(box, 0, 180, false, stroke);
        c.drawLine(cx, top + 66 * u, cx, top + 76 * u, stroke);
        c.drawLine(cx - 8 * u, top + 76 * u, cx + 8 * u, top + 76 * u, stroke);
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        invalidate();
    }
}
