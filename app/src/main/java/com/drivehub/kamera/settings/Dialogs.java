package com.drivehub.kamera.settings;

import com.drivehub.kamera.R;

import android.content.Context;
import android.content.res.Configuration;
import android.view.ContextThemeWrapper;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

/**
 * Builds dialogs at a size that can be read from the driver's seat.
 *
 * <p>Android sizes its dialogs for a phone held at arm's length. This screen is a metre away,
 * behind a steering wheel: at the stock size the confirmation before deleting another app's
 * files, or the explanation of what a diagnostics report contains, are legible only by leaning
 * in — which is the one thing a driver should not do to read a warning.
 *
 * <p>The whole dialog is scaled through a {@link Configuration} rather than restyled piece by
 * piece, because one piece cannot be restyled at all: AppCompat writes the message's appearance
 * into its own layout instead of reading it from a theme attribute, so a theme can enlarge the
 * title and the buttons but never the paragraph in the middle — the part that most needs it. A
 * font scale reaches all of them.
 *
 * <p>Multiplies whatever the system is set to rather than replacing it: a driver who has already
 * enlarged the head unit's font is asking for bigger text, not for ours.
 *
 * <p>Taken from MG4 Simple Launcher, which solved this first.
 */
public final class Dialogs {

    private static final float FONT_SCALE = 1.4f;

    private Dialogs() {
    }

    @NonNull
    public static AlertDialog.Builder builder(@NonNull Context context) {
        return new AlertDialog.Builder(scaled(context));
    }

    /**
     * The context to build a dialog's own views with, for dialogs that assemble their contents
     * by hand. Views made with the activity would keep the unscaled text while the title and
     * buttons around them grew.
     *
     * <p>The activity stays underneath: a dialog needs its window token, and a context made with
     * {@code createConfigurationContext} has none — it throws {@code BadTokenException} the
     * moment it is shown. Overriding the configuration on a wrapper keeps the activity and
     * changes only the font scale.
     */
    @NonNull
    public static Context scaled(@NonNull Context context) {
        Configuration override = new Configuration();
        override.fontScale = context.getResources().getConfiguration().fontScale * FONT_SCALE;
        ContextThemeWrapper wrapper = new ContextThemeWrapper(context, R.style.Theme_DrivehubKamera);
        // Must happen before anything reads resources from the wrapper.
        wrapper.applyOverrideConfiguration(override);
        return wrapper;
    }
}
