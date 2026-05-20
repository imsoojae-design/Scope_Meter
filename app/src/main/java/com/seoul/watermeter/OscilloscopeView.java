package com.seoul.watermeter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 서울시 디지털계량기 프로토콜 V1.2 UART 오실로스코프 뷰
 *
 * TX 파형:
 *   Low(idle) → High(20~50ms) → start bit → data → stop → High(수신대기) → Low(0~100ms후)
 *
 * RX 파형:
 *   Low(idle) → Low(0~100ms) → High(20~50ms) → start bit → data → stop → Low
 *
 * - 핀치 줌: TX/RX 동시 확대/축소 (SharedZoom 콜백)
 * - 좌우 스크롤: 시간축 이동
 */
public class OscilloscopeView extends View {

    private static final float BIT_MS      = 1000f / 1200f;  // 0.833ms
    private static final float MIN_WINDOW  = 30f;
    private static final float MAX_WINDOW  = 3000f;
    private static final float TOTAL_MS    = 3000f;  // 버퍼 전체 범위

    private static class Sample {
        float timeMs; int level;
        Sample(float t, int l) { timeMs=t; level=l; }
    }

    // 줌/스크롤 동기화 인터페이스
    public interface ZoomScrollListener {
        void onZoomScroll(float windowMs, float offsetMs);
    }

    private final List<Sample> samples    = new ArrayList<>();
    private float  windowMs   = 500f;
    private float  offsetMs   = 0f;     // 스크롤 오프셋
    private int    signalColor = Color.parseColor("#FFD700");
    private ZoomScrollListener zoomListener;

    private final Paint bgPaint     = new Paint();
    private final Paint gridPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint signalPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path  sigPath     = new Path();

    private ScaleGestureDetector scaleDetector;
    private GestureDetector      gestureDetector;
    private float lastX = 0f;

    public OscilloscopeView(Context ctx) { super(ctx); init(); }
    public OscilloscopeView(Context ctx, AttributeSet a) { super(ctx, a); init(); }

    private void init() {
        bgPaint.setColor(Color.parseColor("#060D1A"));
        bgPaint.setStyle(Paint.Style.FILL);

        gridPaint.setColor(Color.parseColor("#112240"));
        gridPaint.setStrokeWidth(1f);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setPathEffect(new DashPathEffect(new float[]{3,4}, 0));

        signalPaint.setStrokeWidth(2.5f);
        signalPaint.setStyle(Paint.Style.STROKE);
        signalPaint.setStrokeCap(Paint.Cap.SQUARE);
        signalPaint.setStrokeJoin(Paint.Join.MITER);

        labelPaint.setColor(Color.parseColor("#3A5A8A"));
        labelPaint.setTextSize(18f);
        labelPaint.setTypeface(Typeface.MONOSPACE);

        axisPaint.setColor(Color.parseColor("#1E3A5F"));
        axisPaint.setStrokeWidth(1.5f);
        axisPaint.setStyle(Paint.Style.STROKE);

        // 핀치 줌
        scaleDetector = new ScaleGestureDetector(getContext(),
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector d) {
                    float newWindow = Math.min(MAX_WINDOW,
                        Math.max(MIN_WINDOW, windowMs / d.getScaleFactor()));
                    windowMs = newWindow;
                    clampOffset();
                    if (zoomListener != null) zoomListener.onZoomScroll(windowMs, offsetMs);
                    invalidate();
                    return true;
                }
            });

        // 좌우 스크롤
        gestureDetector = new GestureDetector(getContext(),
            new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onScroll(MotionEvent e1, MotionEvent e2,
                                                   float dx, float dy) {
                    float msPerPx = windowMs / getWidth();
                    offsetMs += dx * msPerPx;
                    clampOffset();
                    if (zoomListener != null) zoomListener.onZoomScroll(windowMs, offsetMs);
                    invalidate();
                    return true;
                }
            });

        reset();
    }

    private void clampOffset() {
        offsetMs = Math.max(0, Math.min(TOTAL_MS - windowMs, offsetMs));
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaleDetector.onTouchEvent(e);
        if (!scaleDetector.isInProgress()) gestureDetector.onTouchEvent(e);
        return true;
    }

    public void setSignalColor(int color) { signalColor=color; invalidate(); }
    public void setZoomScrollListener(ZoomScrollListener l) { zoomListener=l; }

    // 외부에서 줌/스크롤 동기화
    public void syncZoomScroll(float wMs, float oMs) {
        windowMs = wMs; offsetMs = oMs;
        clampOffset();
        invalidate();
    }

    // Idle: Low
    public void reset() {
        samples.clear();
        samples.add(new Sample(0, 0));
        samples.add(new Sample(TOTAL_MS, 0));
        offsetMs = 0;
        postInvalidate();
    }

    /**
     * TX 파형 생성 (프로토콜 V1.2 규정)
     * Low(idle) → High(35ms) → [start+data+stop] → High(수신대기) → Low(50ms후)
     */
    public void addTxBytes(byte[] data, float startMs) {
        samples.clear();

        // 1. 초기 Low (idle)
        samples.add(new Sample(0, 0));

        // 2. 검침 요청 직전까지 Low 유지 → High 전환 (35ms 대기)
        samples.add(new Sample(startMs, 0));  // Low 유지
        samples.add(new Sample(startMs + 1f, 1));  // High 전환

        // 3. start bit 시작 (35ms 후)
        float t = highStart + 35f;

        for (byte b : data) {
            int val = b & 0xFF;
            samples.add(new Sample(t, 0)); t += BIT_MS;       // start bit
            for (int i=0; i<8; i++) {
                samples.add(new Sample(t, (val>>i)&1)); t += BIT_MS;
            }
            samples.add(new Sample(t, 1)); t += BIT_MS;       // stop bit
        }

        // 4. 전송 완료 후 High 유지 (계량기 응답 대기)
        float txEnd = t;
        samples.add(new Sample(txEnd, 1));

        // 5. RX 완료 후 Low 전환 (약 500ms 후 — 실제는 RX 완료 후 0~100ms)
        samples.add(new Sample(txEnd + 500f, 1));
        samples.add(new Sample(txEnd + 550f, 0));
        samples.add(new Sample(TOTAL_MS, 0));

        postInvalidate();
    }

    /**
     * RX 파형 생성 (프로토콜 V1.2 규정)
     * Low(0~100ms 대기) → High(20~50ms) → [start+data+stop] → Low
     */
    public void addRxBytes(byte[] data, float rxStartMs) {
        samples.clear();

        // 1. 초기 Low
        samples.add(new Sample(0, 0));

        // 2. Low 유지 (0~100ms) → 50ms
        float lowEnd = rxStartMs + 50f;
        samples.add(new Sample(rxStartMs, 0));

        // 3. High 전환 (20~50ms 대기) → 35ms
        samples.add(new Sample(lowEnd, 1));

        // 4. start bit 시작
        float t = lowEnd + 35f;

        for (byte b : data) {
            int val = b & 0xFF;
            samples.add(new Sample(t, 0)); t += BIT_MS;
            for (int i=0; i<8; i++) {
                samples.add(new Sample(t, (val>>i)&1)); t += BIT_MS;
            }
            samples.add(new Sample(t, 1)); t += BIT_MS;
        }

        // 5. 전송 완료 후 Low 전환 (0~100ms 후) → 50ms
        samples.add(new Sample(t, 1));
        samples.add(new Sample(t + 50f, 0));
        samples.add(new Sample(TOTAL_MS, 0));

        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w=getWidth(), h=getHeight();
        if (w==0||h==0) return;

        canvas.drawRect(0,0,w,h,bgPaint);

        float padL=28f, padR=6f, padT=6f, padB=20f;
        float plotW=w-padL-padR, plotH=h-padT-padB;
        float highY=padT+plotH*0.08f, lowY=padT+plotH*0.88f;

        // 그리드
        float divMs = windowMs/5f;
        for (int i=0; i<=5; i++) {
            float ms  = offsetMs + i*divMs;
            float x   = padL + (i/(float)5)*plotW;
            canvas.drawLine(x,padT,x,padT+plotH,gridPaint);
            String lbl = ms>=1000 ? String.format("%.1fs",ms/1000) : (int)ms+"ms";
            canvas.drawText(lbl, x-16, h-3, labelPaint);
        }
        canvas.drawLine(padL,highY,padL+plotW,highY,gridPaint);
        canvas.drawLine(padL,lowY, padL+plotW,lowY, gridPaint);
        canvas.drawLine(padL,padT,padL,padT+plotH,axisPaint);

        // H/L 레이블
        Paint lp = new Paint(labelPaint);
        lp.setColor(signalColor);
        canvas.drawText("H",2,highY+6,lp);
        lp.setColor(Color.parseColor("#3A5A8A"));
        canvas.drawText("L",2,lowY+6,lp);

        // 타임스케일
        String scale = divMs>=1000 ? String.format("%.0fs/div",divMs/1000) : (int)divMs+"ms/div";
        lp.setColor(Color.parseColor("#2A4A7A"));
        canvas.drawText(scale, padL+plotW-75, padT+14, lp);

        // 신호
        if (samples.size() < 2) return;
        signalPaint.setColor(signalColor);
        sigPath.reset();

        boolean started = false;
        float viewStart = offsetMs;
        float viewEnd   = offsetMs + windowMs;

        for (int i=0; i<samples.size()-1; i++) {
            Sample a = samples.get(i);
            Sample b2 = samples.get(i+1);
            if (b2.timeMs < viewStart || a.timeMs > viewEnd) continue;

            float t1 = Math.max(a.timeMs, viewStart);
            float t2 = Math.min(b2.timeMs, viewEnd);
            float x1 = padL + ((t1-viewStart)/windowMs)*plotW;
            float x2 = padL + ((t2-viewStart)/windowMs)*plotW;
            float y1 = a.level==1 ? highY : lowY;
            float y2 = b2.level==1 ? highY : lowY;

            if (!started) { sigPath.moveTo(x1,y1); started=true; }
            else sigPath.lineTo(x1,y1);

            if (y1!=y2) { sigPath.lineTo(x2,y1); sigPath.lineTo(x2,y2); }
            else sigPath.lineTo(x2,y2);
        }
        if (started) canvas.drawPath(sigPath,signalPaint);
    }
}
