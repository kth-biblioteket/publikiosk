package se.kth.lib.publikiosk;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.widget.TextView;

/** Nedräkningen i "Är du kvar?": siffran i en ring där den blå bågen krymper med tiden, som i skissen */
public class CountdownView extends TextView {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private float progress = 1f;

    public CountdownView(Context context, AttributeSet attrs) {
        super(context, attrs);
        for (Paint p : new Paint[]{track, arc}) {
            p.setStyle(Paint.Style.STROKE);
        }
        track.setColor(0xFFDEF0FF);
        arc.setColor(0xFF004791);
        arc.setStrokeCap(Paint.Cap.ROUND);
    }

    /** 1 = full båge, 0 = tom */
    public void setProgress(float progress) {
        this.progress = Math.max(0f, Math.min(1f, progress));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // Ringen ritas som i skissen: diameter 104 av 120, tjocklek 10 av 120
        float size = Math.min(getWidth(), getHeight());
        float stroke = size * 10f / 120f;
        float r = size * 52f / 120f;
        track.setStrokeWidth(stroke);
        arc.setStrokeWidth(stroke);
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        box.set(cx - r, cy - r, cx + r, cy + r);
        canvas.drawCircle(cx, cy, r, track);
        canvas.drawArc(box, -90f, 360f * progress, false, arc);
        super.onDraw(canvas);
    }
}
