package io.github.nexalloy.revanced.zalo.calls;

import android.util.Log;

import de.robv.android.xposed.XposedBridge;

/**
 * Thin logging shim shared by the Java half of the call-recording pipeline
 * ({@link CallRecordingOutput}, {@link CallRecordingTranscoder},
 * {@link CallRecordingContacts}, {@link CallRecordingMetadataStore}).
 *
 * <p>Writes to both {@code logcat} (tag {@value #TAG}) and
 * {@link XposedBridge#log}, because the latter is what shows up in the
 * LSPosed Manager "Logs" tab regardless of the host app's own log level. This
 * is debug-diagnostic logging meant to make it obvious, from an LSPosed log
 * dump alone, exactly which stage of finalization ran, skipped, or failed on
 * a given Zalo build.
 */
final class CallRecordingLog {
    private static final String TAG = "NexAlloy-CallRecording";

    private CallRecordingLog() {
    }

    static void d(String message) {
        Log.d(TAG, message);
        XposedBridge.log(TAG + ": " + message);
    }

    static void w(String message) {
        Log.w(TAG, message);
        XposedBridge.log(TAG + " WARN: " + message);
    }

    static void e(String message, Throwable throwable) {
        Log.e(TAG, message, throwable);
        XposedBridge.log(TAG + " ERROR: " + message);
        if (throwable != null) {
            XposedBridge.log(throwable);
        }
    }
}
