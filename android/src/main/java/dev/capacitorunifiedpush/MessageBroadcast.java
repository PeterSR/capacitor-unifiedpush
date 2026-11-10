package dev.capacitorunifiedpush;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

/**
 * Package-restricted broadcast sent for every accepted (decrypted) message, so the app's own
 * native code can react while the WebView is not running.
 */
final class MessageBroadcast {

    static final String ACTION = "dev.capacitorunifiedpush.MESSAGE";
    static final String EXTRA_INSTANCE = "instance";
    static final String EXTRA_DATA = "data";
    static final String EXTRA_ID = "id";
    /**
     * Application meta-data flag, default on. Off values: boolean false, integer 0, or a
     * {@code @string} resource whose value is "false" or "0" (trimmed, case-insensitive).
     */
    static final String META_ENABLED = "dev.capacitorunifiedpush.broadcast_messages";

    private MessageBroadcast() {}

    /** Builds the package-restricted intent. */
    static Intent build(Context context, String instance, String data, String id) {
        Intent i = new Intent(ACTION);
        i.setPackage(context.getPackageName());
        i.putExtra(EXTRA_INSTANCE, instance);
        i.putExtra(EXTRA_DATA, data);
        i.putExtra(EXTRA_ID, id);
        return i;
    }

    /**
     * True unless the meta-data flag is boolean false, integer 0, or a string "false" or "0".
     * Any other value, or no flag, leaves the broadcast on.
     */
    static boolean isEnabled(Context context) {
        try {
            ApplicationInfo info = context
                .getPackageManager()
                .getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA);
            Bundle meta = info.metaData;
            if (meta == null) {
                return true;
            }
            // Read the raw value so that a string "false" or an integer 0 also switch it off.
            Object v = meta.get(META_ENABLED);
            if (v instanceof Boolean) {
                return (Boolean) v;
            }
            if (v instanceof Integer) {
                return ((Integer) v) != 0;
            }
            if (v instanceof String) {
                return !"false".equalsIgnoreCase(((String) v).trim()) && !"0".equals(((String) v).trim());
            }
            return true;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return true;
        }
    }

    static void send(Context context, String instance, String data, String id) {
        if (!isEnabled(context)) {
            return;
        }
        try {
            context.sendBroadcast(build(context, instance, data, id));
        } catch (RuntimeException e) {
            Log.w(NotificationHandler.TAG, "Could not send message broadcast", e);
        }
    }
}
