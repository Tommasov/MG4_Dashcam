package com.drivehub.kamera.dashcam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.drivehub.kamera.dev.DevRuntimeLog;
import com.drivehub.kamera.settings.UiPrefs;

import java.lang.reflect.Method;

/**
 * A dot in the head unit's own status bar, for people who want one.
 *
 * <p>There is no polite way to do this on this car. The factory SystemUI has no status bar from
 * Android in it at all - {@code com.android.systemui.statusbar} is not in the package - and what
 * draws the bar is SAIC's own {@code saicmotor.statusbar.StatusBar}, with a fixed set of icons
 * whose visibility it toggles. No notification icon area, no slots, nothing to register with.
 * The one service it does expose to other apps speaks a media-browser protocol, for the radio.
 *
 * <p>So this draws its own window on top of theirs. It is allowed to because the app is signed
 * with the platform key and runs as the system user, and it is careful about it: the window is
 * as tall as the real status bar - asked of the framework, not guessed - narrow, untouchable,
 * and never focused, so taps pass straight through to whatever is underneath.
 *
 * <p>Off unless asked for, and that is not timidity. Putting something of ours inside the
 * factory interface is the most visible thing this app does, and whether that is welcome
 * depends on who is looking at the car.
 */
public final class StatusIconOverlay {

    private static final String TAG = "StatusIcon";

    /**
     * Everything inside the window, as fractions of the bar's own height.
     *
     * <p>Of the height, and never of density, which is the mistake the first version made: the
     * width was 56dp and the text was sized from the bar, and on this head unit a dp is exactly
     * a pixel while the bar is eighty of them. So the letters were drawn nearly twice as wide as
     * the window that held them, and all that showed was half an R. Sized from one number, the
     * whole thing scales together whatever the screen turns out to be.
     */
    private static final float DOT_RADIUS = 0.14f;
    private static final float LEFT_PAD = 0.16f;
    private static final float GAP = 0.18f;
    private static final float TEXT_SIZE = 0.42f;
    private static final float RIGHT_PAD = 0.16f;

    /** How far in from the edge, so it does not land under one of theirs. */
    private static final int MARGIN_DP = 4;

    /**
     * Every word this can show. The window is made wide enough for the widest of them, so that
     * changing state repaints in place instead of shuffling the dot sideways along the bar -
     * movement in the corner of the eye being the one thing a dashboard should not add.
     */
    private static final String[] LABELS = {"REC", "360", "ERR"};

    /** Measured, not assumed: three bold capitals are wider than they look. */
    private static int windowWidthPx(int barHeightPx) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextSize(barHeightPx * TEXT_SIZE);
        float widest = 0f;
        for (String label : LABELS) {
            widest = Math.max(widest, p.measureText(label));
        }
        return Math.round(barHeightPx * (LEFT_PAD + 2 * DOT_RADIUS + GAP + RIGHT_PAD) + widest);
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Nullable
    private WindowManager windowManager;
    @Nullable
    private DotView view;
    private boolean standardRouteTried;
    private int placedAtPercent = -1;
    private boolean quietlyOff;

    private int wantedPercent() {
        return UiPrefs.getStatusBarIconX(UiPrefs.getPrefs(context));
    }

    public StatusIconOverlay(@NonNull Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Shows the dot in the state the recorder is in, or takes it away.
     *
     * <p>Says so the first time it is asked for something it will not draw. A switch left off
     * and an overlay the window manager refused used to produce the same thing in a report -
     * no line at all - and an evening was spent reading that silence as a failure when it was a
     * switch.
     */
    public void update(@Nullable String status) {
        main.post(() -> {
            if (status == null || RecordingService.STATUS_OFF.equals(status)) {
                if (!quietlyOff) {
                    quietlyOff = true;
                    DevRuntimeLog.add(TAG, status == null
                            ? "not shown: the setting is off"
                            : "not shown: nothing is recording");
                }
                removeNow();
                return;
            }
            quietlyOff = false;
            show(colourFor(status), labelFor(status));
        });
    }

    public void hide() {
        main.post(this::removeNow);
    }

    private void removeNow() {
        if (windowManager != null && view != null) {
            try {
                windowManager.removeView(view);
            } catch (Throwable ignored) {
                // Already gone, which is the state we were asking for.
            }
        }
        view = null;
        windowManager = null;
    }

    /**
     * Three letters, because a coloured dot on its own has to be learned.
     *
     * <p>REC while it is recording, 360 while the factory view has the cameras and we are
     * waiting our turn, ERR when something needs looking at. Somebody who has never read a line
     * about this app can read all three.
     */
    @NonNull
    private String labelFor(@NonNull String status) {
        if (RecordingService.STATUS_PAUSED_OEM.equals(status)) {
            return "360";
        }
        if (RecordingService.STATUS_ERROR.equals(status)
                || RecordingService.STATUS_PARTIAL.equals(status)) {
            return "ERR";
        }
        return "REC";
    }

    private int colourFor(@NonNull String status) {
        if (RecordingService.STATUS_RECORDING.equals(status)) {
            return Color.parseColor("#E53935");
        }
        if (RecordingService.STATUS_PAUSED_OEM.equals(status)
                || RecordingService.STATUS_STARTING.equals(status)) {
            return Color.parseColor("#FFB300");
        }
        return Color.parseColor("#9E9E9E");
    }

    private void show(int colour, @NonNull String label) {
        if (view != null && placedAtPercent == wantedPercent()) {
            view.setState(colour, label);
            return;
        }
        // The dot has been moved: the window has to be laid out again, not just repainted.
        removeNow();
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            return;
        }
        DotView dot = new DotView(context);
        dot.setState(colour, label);

        final float density = context.getResources().getDisplayMetrics().density;
        final int barHeight = statusBarHeightPx();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                windowWidthPx(barHeight),
                barHeight,
                WINDOW_TYPES[0],
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        // Measured from the left so the whole width is reachable, including the middle.
        final int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        final int travel = Math.max(0, screenWidth - lp.width - 2 * Math.round(MARGIN_DP * density));
        placedAtPercent = wantedPercent();
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = Math.round(MARGIN_DP * density) + Math.round(travel * placedAtPercent / 100f);
        lp.y = 0;
        StringBuilder refusals = new StringBuilder();
        for (int type : WINDOW_TYPES) {
            lp.type = type;
            try {
                wm.addView(dot, lp);
                windowManager = wm;
                view = dot;
                DevRuntimeLog.add(TAG, "overlay shown as " + nameOfType(type) + ", "
                        + lp.width + "x" + lp.height + "px at x=" + lp.x
                        + " of " + screenWidth + " (" + placedAtPercent + "%)"
                        + (refusals.length() == 0 ? "" : "; first tried" + refusals));
                break;
            } catch (Throwable t) {
                refusals.append(" ").append(nameOfType(type)).append("=").append(t);
                Log.w(TAG, "window type " + nameOfType(type) + " refused", t);
            }
        }
        if (view == null) {
            DevRuntimeLog.add(TAG, "overlay refused everywhere:" + refusals);
        }
        tryStandardRouteOnce();
    }

    /**
     * Window types to try, highest in the stack first.
     *
     * <p>The first attempt used TYPE_APPLICATION_OVERLAY, which is as high as an ordinary app is
     * allowed to reach - and it is still below TYPE_STATUS_BAR. The window was created without
     * complaint, sat where it was told, and was invisible, because the factory bar is opaque and
     * on top of it. The report said {@code overlay shown} the whole time; only the driver could
     * see that it was not.
     *
     * <p>These two are reserved for the platform, which is exactly what this app is, and both
     * sit above the status bar. If neither is allowed the last one still works as before - and
     * then the log says which one took it, so an invisible dot is never again a mystery.
     */
    private static final int[] WINDOW_TYPES = {
            2006, // TYPE_SYSTEM_OVERLAY: above the status bar, and cannot take input at all
            2017, // TYPE_STATUS_BAR_PANEL: made for things that belong over the bar
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    };

    private static String nameOfType(int type) {
        switch (type) {
            case 2006:
                return "SYSTEM_OVERLAY";
            case 2017:
                return "STATUS_BAR_PANEL";
            default:
                return "APPLICATION_OVERLAY";
        }
    }

    /** The bar's real height, from the framework, because guessing it would show. */
    private int statusBarHeightPx() {
        int id = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
        int h = id > 0 ? context.getResources().getDimensionPixelSize(id) : 0;
        return h > 0 ? h : Math.round(28 * context.getResources().getDisplayMetrics().density);
    }

    /**
     * Asks Android's own status bar API for an icon, once, and writes down what happened.
     *
     * <p>Their SystemUI still implements {@code IStatusBar} - the stub and {@code StatusBarIcon}
     * are both in its dex - so the call does reach their code. Whether their code draws anything
     * is the open question, and the only way to answer it is to look at the bar afterwards: if
     * the dot appears twice, the standard route works and this overlay can go away.
     *
     * <p>All reflection, because {@code StatusBarManager} is not in the public SDK. Being
     * refused here is the expected outcome, not an error.
     */
    private void tryStandardRouteOnce() {
        if (standardRouteTried) {
            return;
        }
        standardRouteTried = true;
        try {
            Object sbm = context.getSystemService("statusbar");
            if (sbm == null) {
                DevRuntimeLog.add(TAG, "standard route: no statusbar service");
                return;
            }
            Method setIcon = sbm.getClass().getMethod(
                    "setIcon", String.class, int.class, int.class, String.class);
            setIcon.invoke(sbm, "dashcam",
                    com.drivehub.kamera.R.drawable.ic_stat_dashcam, 0, "Dashcam");
            DevRuntimeLog.add(TAG, "standard route: setIcon accepted - look at the bar");
        } catch (Throwable t) {
            DevRuntimeLog.add(TAG, "standard route refused: " + t);
        }
    }

    /**
     * The dot and its word, drawn to the bar's own height. No animation.
     *
     * <p>A blinking dot is the convention on a camera and the wrong thing on a windscreen: the
     * one place the eye must not be pulled is the middle of the dashboard. It sits still and
     * says what it is.
     */
    private static final class DotView extends View {

        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private String label = "REC";

        DotView(Context context) {
            super(context);
            dot.setStyle(Paint.Style.FILL);
            text.setColor(Color.WHITE);
            text.setTypeface(Typeface.DEFAULT_BOLD);
        }

        void setState(int colour, String label) {
            this.dot.setColor(colour);
            this.label = label == null ? "" : label;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float h = getHeight();
            final float r = h * DOT_RADIUS;
            final float cx = h * LEFT_PAD + r;
            canvas.drawCircle(cx, h / 2f, r, dot);

            text.setTextSize(h * TEXT_SIZE);
            Paint.FontMetrics fm = text.getFontMetrics();
            // Centred on the bar by the glyphs, not by the line box: the line box carries
            // leading that would push three capitals visibly low.
            float baseline = h / 2f - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(label, cx + r + h * GAP, baseline, text);
        }
    }
}
