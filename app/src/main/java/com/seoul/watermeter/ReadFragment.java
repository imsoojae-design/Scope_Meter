package com.seoul.watermeter;

import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

public class ReadFragment extends Fragment {

    private TextView         tvReading, tvMeterNo, tvDiam, tvTime, tvStatus, tvAddr, tvHexData;
    private Button           btnConnect, btnRequest, btnAddrPlus, btnAddrMinus;
    private Spinner          spinnerRepeat;
    private OscilloscopeView oscTx, oscRx;
    private int              addr = 1;

    // TX 전송 시작 시각 (ms) - RX 파형의 상대적 오프셋 계산용
    private long txStartMs = 0;

    private static final String[] LABELS = {"자동 반복 안함","10초","30초","1분","5분","1시간"};
    private static final int[]    VALS   = {0, 10000, 30000, 60000, 300000, 3600000};

    @Override
    public View onCreateView(LayoutInflater inf, ViewGroup vg, Bundle b) {
        View v = inf.inflate(R.layout.fragment_read, vg, false);

        tvReading    = v.findViewById(R.id.tvReading);
        tvMeterNo    = v.findViewById(R.id.tvMeterNo);
        tvDiam       = v.findViewById(R.id.tvDiam);
        tvTime       = v.findViewById(R.id.tvTime);
        tvStatus     = v.findViewById(R.id.tvStatus);
        tvAddr       = v.findViewById(R.id.tvAddr);
        tvHexData    = v.findViewById(R.id.tvHexData);
        btnConnect   = v.findViewById(R.id.btnConnect);
        btnRequest   = v.findViewById(R.id.btnRequest);
        btnAddrPlus  = v.findViewById(R.id.btnAddrPlus);
        btnAddrMinus = v.findViewById(R.id.btnAddrMinus);
        spinnerRepeat = v.findViewById(R.id.spinnerRepeat);
        oscTx        = v.findViewById(R.id.oscTx);
        oscRx        = v.findViewById(R.id.oscRx);

        oscTx.setSignalColor(Color.parseColor("#FFD700")); // 노란색 (CH1)
        oscRx.setSignalColor(Color.parseColor("#00CFFF")); // 시안색 (CH2)

        btnAddrPlus.setOnClickListener(x -> {
            if (addr < 250) { addr++; tvAddr.setText(String.valueOf(addr)); }
        });
        btnAddrMinus.setOnClickListener(x -> {
            if (addr > 1) { addr--; tvAddr.setText(String.valueOf(addr)); }
        });

        btnConnect.setOnClickListener(x -> {
            if (MainActivity.instance == null) return;
            if (MainActivity.instance.isConnected()) {
                MainActivity.instance.disconnectUsb();
            } else {
                MainActivity.instance.connectUsb();
            }
        });

        btnRequest.setOnClickListener(x -> {
            if (MainActivity.instance != null)
                MainActivity.instance.sendRequest(addr);
        });

        ArrayAdapter<String> adp = new ArrayAdapter<>(
            requireContext(), android.R.layout.simple_spinner_dropdown_item, LABELS);
        spinnerRepeat.setAdapter(adp);
        spinnerRepeat.setOnItemSelectedListener(
            new android.widget.AdapterView.OnItemSelectedListener() {
                public void onItemSelected(android.widget.AdapterView<?> p,
                                           View vi, int pos, long id) {
                    if (MainActivity.instance != null)
                        MainActivity.instance.setAutoInterval(VALS[pos], addr);
                }
                public void onNothingSelected(android.widget.AdapterView<?> p) {}
            });

        return v;
    }

    // TX 파형 (요청 전송) - 20ms 오프셋 후 시작 (High→Low 전환 후 start bit)
    public void showTxWave(byte[] data) {
        if (oscTx == null) return;
        txStartMs = System.currentTimeMillis();
        oscTx.reset();
        oscTx.addBytes(data, 30f); // 30ms 오프셋 (High 대기)
        oscRx.reset(); // RX 초기화
    }

    // RX 파형 (응답 수신) - TX 완료 후 실제 경과시간 기반 오프셋
    public void showRxWave(byte[] data, String hexStr) {
        if (oscRx == null) return;
        // TX 전송 후 경과시간 계산
        float elapsed = txStartMs > 0
            ? (float)(System.currentTimeMillis() - txStartMs)
            : 100f;
        // 최소 50ms, 최대 800ms
        float offset = Math.min(Math.max(elapsed, 50f), 800f);
        oscRx.addBytes(data, offset);
        if (tvHexData != null) tvHexData.setText(hexStr);
    }

    public void onConnected(boolean on) {
        if (btnConnect == null) return;
        if (on) {
            btnConnect.setText("연결 끊기");
            btnConnect.setBackgroundTintList(
                ContextCompat.getColorStateList(requireContext(), R.color.red));
            btnRequest.setEnabled(true);
        } else {
            btnConnect.setText("USB 시리얼 연결");
            btnConnect.setBackgroundTintList(
                ContextCompat.getColorStateList(requireContext(), R.color.accent2));
            btnRequest.setEnabled(false);
            if (tvStatus != null) {
                tvStatus.setText("대기 중");
                tvStatus.setTextColor(requireContext().getColor(R.color.muted));
                tvStatus.setBackgroundResource(R.drawable.bg_pill_gray);
            }
            if (oscTx != null) oscTx.reset();
            if (oscRx != null) oscRx.reset();
            if (tvHexData != null) tvHexData.setText("—");
        }
    }

    public void updateReading(MeterProtocol.ParseResult r) {
        if (tvReading == null) return;
        tvReading.setText(r.readingFmt());
        tvMeterNo.setText(r.meterNo);
        tvDiam.setText(r.diameter > 0 ? r.diameter + " mm" : "—");
        tvTime.setText(r.timestamp);

        if (r.hasWarning()) {
            tvStatus.setText("⚠ " + r.statusString());
            tvStatus.setTextColor(requireContext().getColor(R.color.yellow));
            tvStatus.setBackgroundResource(R.drawable.bg_pill_gray);
        } else {
            tvStatus.setText("✓ 정상");
            tvStatus.setTextColor(requireContext().getColor(R.color.green));
            tvStatus.setBackgroundResource(R.drawable.bg_pill_green);
        }
        if (!r.checksumOk) {
            tvStatus.setText(tvStatus.getText() + " 체크섬오류");
            tvStatus.setTextColor(requireContext().getColor(R.color.red));
        }
    }
}
