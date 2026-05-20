package com.seoul.watermeter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.ScaleGestureDetector;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class OscilloscopeView extends View {

    private static final float BIT_MS     = 1000f / 1200f;
    private static final float MIN_WINDOW = 50f;
    private static final float MAX_WINDOW = 2000f;

    private static class Sample {
        float timeMs; int level;
        Sample(float t, int l) { timeMs=t; level=l; }
    }

    private final List<Sample> samples = new ArrayList<>();
    private float windowMs   = 500f;
    private int   signalColor = Color.parseColor("#FFD700");

    private final Paint bgPaint     = new Paint();
    private final Paint gridPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint signalPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path  sigPath     = new Path();
    private ScaleGestureDetector scaleDetector;

    public OscilloscopeView(Context ctx) { super(ctx); init(); }
    public OscilloscopeView(Context ctx, AttributeSet a) { super(ctx,a); init(); }

    private void init() {
        bgPaint.setColor(Color.parseColor("#060D1A"));
        bgPaint.setStyle(Paint.Style.FILL);
        gridPaint.setColor(Color.parseColor("#112240"));
        gridPaint.setStrokeWidth(1f);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setPathEffect(new DashPathEffect(new float[]{3,4}, 0));
        signalPaint.setStrokeWidth(2f);
        signalPaint.setStyle(Paint.Style.STROKE);
        signalPaint.setStrokeCap(Paint.Cap.SQUARE);
        signalPaint.setStrokeJoin(Paint.Join.MITER);
        labelPaint.setColor(Color.parseColor("#3A5A8A"));
        labelPaint.setTextSize(18f);
        labelPaint.setTypeface(Typeface.MONOSPACE);
        axisPaint.setColor(Color.parseColor("#1E3A5F"));
        axisPaint.setStrokeWidth(1.5f);
        axisPaint.setStyle(Paint.Style.STROKE);

        scaleDetector = new ScaleGestureDetector(getContext(),
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector d) {
                    windowMs = Math.min(MAX_WINDOW,
                        Math.max(MIN_WINDOW, windowMs / d.getScaleFactor()));
                    invalidate();
                    return true;
                }
            });
        reset();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaleDetector.onTouchEvent(e);
        return true;
    }

    public void setSignalColor(int color) { signalColor=color; invalidate(); }

    public void reset() {
        samples.clear();
        samples.add(new Sample(0, 0));
        samples.add(new Sample(MAX_WINDOW, 0));
        postInvalidate();
    }

    // boolean idleLow 파라미터 포함 (3개)
    public void addBytes(byte[] data, float offsetMs, boolean idleLow) {
        samples.clear();
        int idle = idleLow ? 0 : 1;
        samples.add(new Sample(0, idle));
        float t = offsetMs;
        for (byte b : data) {
            int val = b & 0xFF;
            samples.add(new Sample(t, 0)); t += BIT_MS;
            for (int i=0; i<8; i++) {
                samples.add(new Sample(t, (val>>i)&1)); t += BIT_MS;
            }
            samples.add(new Sample(t, 1)); t += BIT_MS;
        }
        samples.add(new Sample(t, idle));
        samples.add(new Sample(MAX_WINDOW, idle));
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w=getWidth(), h=getHeight();
        if (w==0||h==0) return;
        canvas.drawRect(0,0,w,h,bgPaint);
        float padL=26f, padR=6f, padT=6f, padB=20f;
        float plotW=w-padL-padR, plotH=h-padT-padB;
        float highY=padT+plotH*0.08f, lowY=padT+plotH*0.88f;
        float divMs=windowMs/5f;
        for (int i=0; i<=5; i++) {
            float ms=i*divMs;
            float x=padL+(ms/windowMs)*plotW;
            canvas.drawLine(x,padT,x,padT+plotH,gridPaint);
            if (i>0) {
                String lbl=ms>=1000?(int)(ms/1000)+"s":(int)ms+"ms";
                canvas.drawText(lbl,x-14,h-3,labelPaint);
            }
        }
        canvas.drawLine(padL,highY,padL+plotW,highY,gridPaint);
        canvas.drawLine(padL,lowY, padL+plotW,lowY, gridPaint);
        canvas.drawLine(padL,padT,padL,padT+plotH,axisPaint);
        Paint lp=new Paint(labelPaint);
        lp.setColor(signalColor);
        canvas.drawText("H",2,highY+6,lp);
        lp.setColor(Color.parseColor("#3A5A8A"));
        canvas.drawText("L",2,lowY+6,lp);
        String scale=divMs>=1000?(int)(divMs/1000)+"s/div":(int)divMs+"ms/div";
        lp.setColor(Color.parseColor("#2A4A7A"));
        canvas.drawText(scale,padL+plotW-70,padT+14,lp);
        if (samples.isEmpty()) return;
        signalPaint.setColor(signalColor);
        sigPath.reset();
        sigPath.moveTo(padL, samples.get(0).level==1?highY:lowY);
        for (int i=1; i<samples.size(); i++) {
            Sample s=samples.get(i);
            float x=padL+Math.min(s.timeMs/windowMs,1f)*plotW;
            float prevY=samples.get(i-1).level==1?highY:lowY;
            float curY =s.level==1?highY:lowY;
            if (curY!=prevY) { sigPath.lineTo(x,prevY); sigPath.lineTo(x,curY); }
            else sigPath.lineTo(x,curY);
        }
        canvas.drawPath(sigPath,signalPaint);
    }
}
