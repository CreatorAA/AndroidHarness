package com.androidharness.app.androiduse;

import android.app.Activity;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.graphics.Canvas;
import android.graphics.Paint;

/** Standalone instrumentation-APK window; a synthetic swipe must not be accepted as a held drag. */
public final class DragTimingProbeActivity extends Activity {
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(new Probe());
    }

    private final class Probe extends View {
        private long down, firstMove;
        private float origin;
        private int pointer, downs;
        private boolean single;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private String label = "Drag timing ready";
        Probe() { super(DragTimingProbeActivity.this); setContentDescription(label); }
        @Override public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downs++; down = event.getEventTime(); firstMove = -1; origin = event.getX();
                    pointer = event.getPointerId(0); single = true;
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_CANCEL:
                    android.util.Log.i("HarnessDragTiming", "additional pointer or cancel during gesture");
                    single = false; return true;
                case MotionEvent.ACTION_MOVE:
                    single &= event.getPointerCount() == 1 && event.getPointerId(0) == pointer;
                    if (firstMove < 0 && Math.abs(event.getX() - origin) > ViewConfiguration.get(getContext()).getScaledTouchSlop())
                        firstMove = event.getEventTime() - down;
                    return true;
                case MotionEvent.ACTION_UP:
                    boolean arrived = origin < getWidth() * .4f && event.getX() > getWidth() * .6f;
                    long timeout = ViewConfiguration.getLongPressTimeout();
                    boolean held = firstMove >= timeout && firstMove >= 0;
                    label = !arrived || !single || firstMove < 0 ? "gesture rejected" :
                            (held ? "held drag accepted" : "ordinary swipe");
                    label += "; dwellMs=" + firstMove + "; thresholdMs=" + timeout + "; downs=" + downs +
                            "; onePointer=" + single + "; releaseInTarget=" + arrived;
                    android.util.Log.i("HarnessDragTiming", label);
                    setContentDescription(label); invalidate();
                    return true;
                default: return true;
            }
        }
        @Override protected void onDraw(Canvas c) {
            c.drawColor(0xfff5f2eb);
            paint.setColor(0xff157250); c.drawCircle(getWidth()*.25f, getHeight()*.5f, 56, paint);
            paint.setColor(0xffc55f37); c.drawCircle(getWidth()*.75f, getHeight()*.5f, 70, paint);
            paint.setColor(0xff222222); paint.setTextSize(36);
            c.drawText(label.split(";")[0], 30, getHeight()*.34f, paint);
        }
    }
}
