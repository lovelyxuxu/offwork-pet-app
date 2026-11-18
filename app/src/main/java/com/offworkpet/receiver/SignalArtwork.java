package com.offworkpet.receiver;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Small original vector illustration; stays crisp at every screen density. */
final class SignalArtwork extends View {
    private final Paint p = new Paint(3);
    SignalArtwork(Context context) { super(context); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
    private void fill(Canvas c, int color, float left, float top, float right, float bottom, float radius) {
        p.setStyle(Paint.Style.FILL); p.setColor(color); c.drawRoundRect(left, top, right, bottom, radius, radius, p);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save(); canvas.scale(getWidth() / 180f, getHeight() / 164f);
        p.setColor(0xFFECE7FB); canvas.drawCircle(91, 81, 66, p);
        p.setColor(0xFFD9D1EF); canvas.drawOval(40, 139, 147, 149, p);
        fill(canvas, 0xFF39334E, 106, 60, 153, 135, 12);
        fill(canvas, 0xFFF9F7FF, 110, 65, 149, 130, 9);
        fill(canvas, 0xFFB1D5B5, 119, 83, 140, 105, 7);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2.4f); p.setStrokeCap(Paint.Cap.ROUND); p.setColor(0xFF447657);
        canvas.drawLine(124, 94, 128, 98, p); canvas.drawLine(128, 98, 135, 90, p);
        p.setStyle(Paint.Style.FILL);
        fill(canvas, 0xFFD7CDEF, 120, 115, 139, 118, 2);
        fill(canvas, 0xFF7560B5, 57, 35, 69, 54, 6);
        p.setColor(0xFFFFCA9D); canvas.drawCircle(66, 119, 12, p);
        Path bell = new Path(); bell.moveTo(26, 108); bell.cubicTo(38, 98, 33, 76, 40, 61);
        bell.cubicTo(46, 46, 74, 41, 87, 57); bell.cubicTo(99, 72, 93, 94, 104, 105);
        bell.cubicTo(108, 111, 102, 117, 96, 118); bell.lineTo(32, 118); bell.cubicTo(24, 118, 20, 113, 26, 108);
        p.setColor(0xFFA28CD9); canvas.drawPath(bell, p);
        p.setColor(0xFFBCA8E9); canvas.drawOval(36, 64, 58, 100, p);
        p.setColor(0xFF3D3451); canvas.drawOval(52, 79, 57, 87, p); canvas.drawOval(76, 79, 81, 87, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2.6f); canvas.drawArc(new RectF(60, 86, 73, 99), 20, 140, false, p);
        p.setStyle(Paint.Style.FILL); p.setColor(0xFFF0BCD0); canvas.drawOval(43, 88, 53, 93, p); canvas.drawOval(80, 88, 90, 93, p);
        star(canvas, 131, 34, 9, 0xFF83B797); star(canvas, 27, 56, 5, 0xFFE1A077);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3); p.setColor(0xFF8F7DC0);
        canvas.drawArc(new RectF(86, 31, 116, 63), -70, 65, false, p);
        canvas.drawArc(new RectF(88, 22, 127, 66), -70, 65, false, p);
        p.setStyle(Paint.Style.FILL); canvas.restore();
    }
    private void star(Canvas c, float x, float y, float size, int color) {
        Path path = new Path(); path.moveTo(x, y-size); path.quadTo(x+2, y-2, x+size, y);
        path.quadTo(x+2, y+2, x, y+size); path.quadTo(x-2, y+2, x-size, y); path.quadTo(x-2, y-2, x, y-size);
        p.setColor(color); c.drawPath(path, p);
    }
}
