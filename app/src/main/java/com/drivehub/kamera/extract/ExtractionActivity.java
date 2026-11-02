package com.drivehub.kamera.extract;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.drivehub.kamera.R;
import com.drivehub.kamera.dev.DevRuntimeLog;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;

/**
 * What the car shows while it is handing footage over.
 *
 * <p>It owns nothing: {@link ExtractionService} holds the phase, and this reads its snapshot
 * once a second and draws it. That way the phase survives the screen being left, and closing
 * this screen by mistake does not take the access point down in the middle of a download.
 */
public class ExtractionActivity extends Activity {

    private static final String TAG = "Extraction";
    private static final long REFRESH_MS = 1000L;

    private final Handler main = new Handler(Looper.getMainLooper());

    private TextView state;
    private TextView trouble;
    private TextView network;
    private TextView url;
    private TextView qrHint;
    private ImageView qr;

    private String drawnPayload;
    private Runnable refresh;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_extraction);

        state = findViewById(R.id.tvExtractionState);
        trouble = findViewById(R.id.tvExtractionTrouble);
        network = findViewById(R.id.tvExtractionNetwork);
        url = findViewById(R.id.tvExtractionUrl);
        qrHint = findViewById(R.id.tvExtractionQrHint);
        qr = findViewById(R.id.ivExtractionQr);

        Button leave = findViewById(R.id.btnExtractionLeave);
        leave.setOnClickListener(v -> {
            ExtractionService.leave(this);
            finish();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh = new Runnable() {
            @Override
            public void run() {
                draw(ExtractionService.snapshot());
                main.postDelayed(this, REFRESH_MS);
            }
        };
        main.post(refresh);
    }

    @Override
    protected void onPause() {
        if (refresh != null) {
            main.removeCallbacks(refresh);
            refresh = null;
        }
        super.onPause();
    }

    private void draw(ExtractionService.Snapshot s) {
        switch (s.phase) {
            case STARTING:
                state.setText(R.string.extraction_state_starting);
                break;
            case READY:
                state.setText(s.requests > 0
                        ? getString(R.string.extraction_state_ready_requests, s.requests)
                        : getString(R.string.extraction_state_ready));
                break;
            case WAITING_FOR_HOTSPOT:
                state.setText(R.string.extraction_state_waiting_hotspot);
                break;
            case FAILED:
                state.setText(R.string.extraction_state_failed);
                break;
            case OFF:
            default:
                // The phase ended under us - the car moved, the time ran out, somebody else
                // stopped it. Saying so and closing is the honest ending.
                state.setText(R.string.extraction_state_over);
                network.setText("");
                url.setText("");
                qr.setVisibility(View.GONE);
                qrHint.setVisibility(View.GONE);
                trouble.setVisibility(View.GONE);
                main.postDelayed(this::finish, 1500L);
                return;
        }

        if (s.trouble == null || s.trouble.isEmpty()) {
            trouble.setVisibility(View.GONE);
        } else {
            trouble.setText(s.trouble);
            trouble.setVisibility(View.VISIBLE);
        }

        if (s.ssid == null) {
            network.setText("");
        } else if (s.passphrase == null || s.passphrase.isEmpty()) {
            network.setText(getString(R.string.extraction_network, s.ssid));
        } else {
            network.setText(getString(R.string.extraction_network_with_password,
                    s.ssid, s.passphrase));
        }

        String address = s.url();
        url.setText(address == null ? "" : address);

        if (s.qrPayload == null) {
            qr.setVisibility(View.GONE);
            qrHint.setVisibility(View.GONE);
        } else {
            if (!s.qrPayload.equals(drawnPayload)) {
                Bitmap bitmap = encode(s.qrPayload, 720);
                if (bitmap != null) {
                    qr.setImageBitmap(bitmap);
                    drawnPayload = s.qrPayload;
                }
            }
            boolean drawn = drawnPayload != null;
            qr.setVisibility(drawn ? View.VISIBLE : View.GONE);
            qrHint.setVisibility(drawn ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * The QR code itself.
     *
     * <p>Error correction M rather than L: this is read off a glossy screen in daylight, often
     * at an angle, and the few extra modules cost nothing at this size. One module of quiet zone
     * is enough because the image sits on its own white field in the layout.
     */
    private static Bitmap encode(String payload, int pixels) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 1);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            BitMatrix matrix = new QRCodeWriter()
                    .encode(payload, BarcodeFormat.QR_CODE, pixels, pixels, hints);
            int width = matrix.getWidth();
            int height = matrix.getHeight();
            int[] row = new int[width];
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    row[x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
                bitmap.setPixels(row, 0, width, 0, y, width, 1);
            }
            return bitmap;
        } catch (Throwable t) {
            DevRuntimeLog.add(TAG, "could not draw the QR code: " + t);
            return null;
        }
    }
}
