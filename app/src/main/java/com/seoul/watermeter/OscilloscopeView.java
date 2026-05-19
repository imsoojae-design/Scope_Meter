package com.seoul.watermeter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 실시간 디지털 파형 오실로스코프 뷰
 * 1200 bps UART 신호를 시각화 (High=1, Low=0)
 */
public class OscilloscopeView extends View {

    private static final int MAX_BITS   = 120;   // 화면에 표시할 최대 비트 수
    private static final int BIT_PIXELS = 6;     // 비트당 픽셀 너비

    private final Paint gridPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint signalPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint    = new Paint();
    private final Path  signalPath = new Path();

    private final Deque<Integer> bits = new ArrayDeque<>(); // 0 or 1
    private int   signalColor = Color.parseColor("#38BDF8"); // accent (TX)
    private boolean isIdle    = true;

    public OscilloscopeView(Context ctx) {
        super(ctx);
        init();
    }

    public OscilloscopeView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        init();
    }

    private void init() {
        bgPaint.setColor(Color.parseColor("#0A0F1E"));
        bgPaint.setStyle(Paint.Style.FILL);

        gridPaint.setColor(Color.parseColor("#1E3A5F"));
        gridPaint.setStrokeWidth(1f);
        gridPaint.setStyle(Paint.Style.STROKE);

        signalPaint.setStrokeWidth(2.5f);
        signalPaint.setStyle(Paint.Style.STROKE);
        signalPaint.setStrokeCap(Paint.Cap.SQUARE);
        signalPaint.setStrokeJoin(Paint.Join.MITER);

        // 기본: High level (idle)
        for (int i = 0; i < MAX_BITS; i++) bits.addLast(1);
    }

    public void setSignalColor(int color) {
        signalColor = color;
        invalidate();
    }

    // ── 바이트 데이터로 UART 비트열 추가 ─────────────────
    public void addBytes(byte[] data) {
        isIdle = false;
        for (byte b : data) {
            // UART: Start(0) + 8 data bits LSB first + Stop(1)
            bits.addLast(0); // start bit
            int val = b & 0xFF;
            for (int i = 0; i < 8; i++) {
                bits.addLast((val >> i) & 1);
            }
            bits.addLast(1); // stop bit
        }
        // 버퍼 크기 제한
        while (bits.size() > MAX_BITS) bits.removeFirst();
        postInvalidate();
    }

    // ── Idle (High) 상태로 리셋 ───────────────────────────
    public void setIdle() {
        isIdle = true;
        bits.clear();
        for (int i = 0; i < MAX_BITS; i++) bits.addLast(1);
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        // 배경
        canvas.drawRect(0, 0, w, h, bgPaint);

        // 그리드 (중간선)
        float midY = h / 2f;
        canvas.drawLine(0, midY, w, midY, gridPaint);
        canvas.drawLine(0, 1, w, 1, gridPaint);
        canvas.drawLine(0, h - 1, w, h - 1, gridPaint);

        // 신호 그리기
        signalPaint.setColor(signalColor);
        signalPath.reset();

        float highY = h * 0.1f;  // High level Y
        float lowY  = h * 0.85f; // Low level Y
        float bitW  = (float) w / MAX_BITS;

        Integer[] bitArr = bits.toArray(new Integer[0]);
        int startIdx = Math.max(0, bitArr.length - MAX_BITS);

        float x = 0;
        int prevBit = bitArr.length > 0 ? bitArr[startIdx] : 1;
        float startY = prevBit == 1 ? highY : lowY;
        signalPath.moveTo(x, startY);

        for (int i = startIdx; i < bitArr.length; i++) {
            int bit = bitArr[i];
            float y = bit == 1 ? highY : lowY;
            float nextX = x + bitW;

            if (bit != prevBit) {
                // 수직 전환
                signalPath.lineTo(x, y);
            }
            signalPath.lineTo(nextX, y);

            prevBit = bit;
            x = nextX;
        }

        // 남은 공간은 현재 레벨 유지
        signalPath.lineTo(w, prevBit == 1 ? highY : lowY);

        canvas.drawPath(signalPath, signalPaint);

        // High/Low 레이블
        Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        labelPaint.setTextSize(18f);
        labelPaint.setColor(Color.parseColor("#64748B"));
        canvas.drawText("H", w - 20, highY + 6, labelPaint);
        canvas.drawText("L", w - 20, lowY + 6, labelPaint);
    }
}
