package com.seoul.watermeter;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

public class ReadFragment extends Fragment {

    private TextView         tvReading, tvMeterNo, tvDiam, tvTime, tvStatus, tvAddr, tvHexData;
    private Button           btnConnect, btnRequest, btnAddrPlus, btnAddrMinus;
    private OscilloscopeView oscTx, oscRx;
    private int              addr = 1;
    private long             txStartMs = 0;

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
        oscTx        = v.findViewById(R.id.oscTx);
        oscRx        = v.findViewById(R.id.oscRx);

        oscTx.setSignalColor(Color.parseColor("#FFD700"));
        oscRx.setSignalColor(Color.parseColor("#00CFFF"));

        btnAddrPlus.setOnClickListener(x -> {
            if (addr < 250) { addr++; tvAddr.setText(String.valueOf(addr)); }
        });
        btnAddrMinus.setOnClickListener(x -> {
            if (addr > 1) { addr--; tvAddr.setText(String.valueOf(addr)); }
        });

        btnConnect.setOnClickListener(x -> {
            if (MainActivity.instance == null) return;
            if (MainActivity.instance.isConnected())
                MainActivity.instance.disconnectUsb();
            else
                MainActivity.instance.connectUsb();
        });

        btnRequest.setOnClickListener(x -> {
            if (MainActivity.instance != null)
                MainActivity.instance.sendRequest(addr);
        });

        tvHexData.setOnLongClickListener(x -> {
            String hex = tvHexData.getText().toString();
            if (!hex.isEmpty() && !hex.startsWith("—")) {
                ClipboardManager cm = (ClipboardManager)
                    requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("HEX", hex));
                Toast.makeText(requireContext(), "HEX 복사됨", Toast.LENGTH_SHORT).show();
            }
            return true;
        });

        return v;
    }

    public void showTxWave(byte[] data) {
        if (oscTx == null) return;
        txStartMs = System.currentTimeMillis();
        oscTx.reset();
        oscTx.addBytes(data, 30f, true);
        oscRx.reset();
    }

    public void showRxWave(byte[] data, String hexStr) {
        if (oscRx == null) return;
        float elapsed = txStartMs > 0
            ? (float)(System.currentTimeMillis() - txStartMs) : 150f;
        float offset = Math.min(Math.max(elapsed, 50f), 450f);
        oscRx.addBytes(data, offset, true);
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
            if (tvHexData != null) tvHexData.setText("— (롱클릭 복사)");
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
