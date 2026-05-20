package com.seoul.watermeter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 실시간 UART 오실로스코프 뷰
 * 1200 bps: 1 bit = 0.833ms
 * 표시 범위: 0 ~ 1000ms (가로 전체)
 */
public class OscilloscopeView extends View {

    // 표시 시간 범위 (ms)
    private static final float WINDOW_MS   = 1000f;
    // 1200bps → 1bit = 0.833ms
    private static final float BIT_MS      = 1000f / 1200f;

    // 샘플 포인트: (timeMs, level 0/1)
    private static class Sample {
        float timeMs;
        int   level; // 0=Low, 1=High
        Sample(float t, int l) { timeMs=t; level=l; }
    }

    private final List<Sample> samples = new ArrayList<>();
    private float  startTimeMs = 0;
    private int    signalColor = Color.parseColor("#38BDF8");
    private boolean hasData    = false;

    // Paints
    private final Paint bgPaint     = new Paint();
    private final Paint gridPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint signalPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path  sigPath     = new Path();

    public OscilloscopeView(Context ctx) { super(ctx); init(); }
    public OscilloscopeView(Context ctx, AttributeSet a) { super(ctx,a); init(); }

    private void init() {
        bgPaint.setColor(Color.parseColor("#060D1A"));
        bgPaint.setStyle(Paint.Style.FILL);

        gridPaint.setColor(Color.parseColor("#0F2040"));
        gridPaint.setStrokeWidth(1f);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setPathEffect(new DashPathEffect(new float[]{4,4}, 0));

        signalPaint.setStrokeWidth(2f);
        signalPaint.setStyle(Paint.Style.STROKE);
        signalPaint.setStrokeCap(Paint.Cap.SQUARE);
        signalPaint.setStrokeJoin(Paint.Join.MITER);

        labelPaint.setColor(Color.parseColor("#3A5A8A"));
        labelPaint.setTextSize(20f);
        labelPaint.setTypeface(Typeface.MONOSPACE);

        axisPaint.setColor(Color.parseColor("#1E3A5F"));
        axisPaint.setStrokeWidth(1.5f);
        axisPaint.setStyle(Paint.Style.STROKE);

        // 초기 Idle High
        reset();
    }

    public void setSignalColor(int color) {
        signalColor = color;
        invalidate();
    }

    // Idle 상태로 리셋
    public void reset() {
        samples.clear();
        hasData = false;
        startTimeMs = 0;
        // 전체 High
        samples.add(new Sample(0, 1));
        samples.add(new Sample(WINDOW_MS, 1));
        postInvalidate();
    }

    // 바이트 배열로 UART 비트열 생성 (실제 타이밍)
    public void addBytes(byte[] data, float offsetMs) {
        if (!hasData) {
            samples.clear();
            hasData = true;
            // 전송 전 High
            samples.add(new Sample(0, 1));
        }

        float t = offsetMs;

        for (byte b : data) {
            int val = b & 0xFF;

            // Start bit (Low, 0.833ms)
            samples.add(new Sample(t, 0));
            t += BIT_MS;

            // 8 data bits LSB first
            for (int i = 0; i < 8; i++) {
                int bit = (val >> i) & 1;
                samples.add(new Sample(t, bit));
                t += BIT_MS;
            }

            // Stop bit (High, 0.833ms)
            samples.add(new Sample(t, 1));
            t += BIT_MS;
        }

        // 전송 후 High 유지
        samples.add(new Sample(t, 1));
        samples.add(new Sample(WINDOW_MS, 1));

        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        // 배경
        canvas.drawRect(0, 0, w, h, bgPaint);

        float padL = 28f, padR = 8f, padT = 6f, padB = 18f;
        float plotW = w - padL - padR;
        float plotH = h - padT - padB;

        float highY = padT + plotH * 0.08f;
        float lowY  = padT + plotH * 0.88f;

        // 그리드 (100ms 간격)
        for (int ms = 0; ms <= 1000; ms += 100) {
            float x = padL + (ms / WINDOW_MS) * plotW;
            canvas.drawLine(x, padT, x, padT + plotH, gridPaint);
            // ms 레이블 (200ms 간격)
            if (ms % 200 == 0 && ms > 0) {
                canvas.drawText(ms + "", x - 14, h - 3, labelPaint);
            }
        }

        // 수평 축선
        canvas.drawLine(padL, highY, padL + plotW, highY, gridPaint);
        canvas.drawLine(padL, lowY,  padL + plotW, lowY,  gridPaint);

        // Y축
        canvas.drawLine(padL, padT, padL, padT + plotH, axisPaint);

        // H/L 레이블
        labelPaint.setColor(signalColor);
        canvas.drawText("H", 2, highY + 6, labelPaint);
        labelPaint.setColor(Color.parseColor("#3A5A8A"));
        canvas.drawText("L", 2, lowY + 6, labelPaint);

        // 신호 경로 그리기
        if (samples.isEmpty()) return;

        signalPaint.setColor(signalColor);
        sigPath.reset();

        Sample first = samples.get(0);
        float fx = padL + (first.timeMs / WINDOW_MS) * plotW;
        float fy = first.level == 1 ? highY : lowY;
        sigPath.moveTo(fx, fy);

        for (int i = 1; i < samples.size(); i++) {
            Sample s = samples.get(i);
            float x = padL + Math.min(s.timeMs / WINDOW_MS, 1f) * plotW;
            float prevY = samples.get(i-1).level == 1 ? highY : lowY;
            float curY  = s.level == 1 ? highY : lowY;

            if (curY != prevY) {
                // 수직 전환
                sigPath.lineTo(x, prevY);
                sigPath.lineTo(x, curY);
            } else {
                sigPath.lineTo(x, curY);
            }
        }

        canvas.drawPath(sigPath, signalPaint);

        // ms 단위 타임스케일 표시
        labelPaint.setColor(Color.parseColor("#3A5A8A"));
        canvas.drawText("100ms/div", padL + plotW - 80, padT + 14, labelPaint);
    }
}
