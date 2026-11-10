package dev.capacitorunifiedpush;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.security.SecureRandom;
import org.json.JSONException;
import org.json.JSONObject;

/** Shows, updates and cancels the notification described by a {@link NotificationSpec}. */
final class NotificationHandler {

    static final String TAG = "UnifiedPushPlugin";
    static final String DEFAULT_CHANNEL_ID = "unifiedpush_default";

    static final String EXTRA_NOTIFICATION_ID = "dev.capacitorunifiedpush.NOTIFICATION_ID";
    static final String EXTRA_ACTION_ID = "dev.capacitorunifiedpush.ACTION_ID";
    static final String EXTRA_DATA = "dev.capacitorunifiedpush.DATA";
    static final String EXTRA_NONCE = "dev.capacitorunifiedpush.NONCE";
    static final String INTENT_ACTION = "dev.capacitorunifiedpush.NOTIFICATION_ACTION";

    private static final SecureRandom RANDOM = new SecureRandom();

    private NotificationHandler() {}

    /**
     * Applies the operation.
     *
     * @return true when the notification was shown, updated or cancelled; false when it was skipped
     *     (nothing to show, notifications disabled)
     */
    static boolean handle(Context context, NotificationSpec spec, PushStore store) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return false;
        }
        int numericId = NotificationSpec.numericId(spec.id);
        if (spec.cancel) {
            nm.cancel(spec.id, numericId);
            store.clearNonces(spec.id);
            store.removeNotification(spec.id);
            return true;
        }
        if (spec.title.isEmpty() && spec.body.isEmpty()) {
            Log.w(TAG, "Ignoring notification without title and body");
            return false;
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            Log.w(TAG, "Notifications are disabled or POST_NOTIFICATIONS is not granted, not showing");
            return false;
        }

        String channelId = resolveChannel(context, nm, spec.channelId);
        NotificationCompat.Builder b = new NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_unifiedpush)
            .setContentTitle(spec.title)
            .setContentText(spec.body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(spec.body))
            .setOngoing(spec.ongoing)
            .setAutoCancel(!spec.ongoing)
            // Updates to an existing notification should not alert again.
            .setOnlyAlertOnce(true)
            .setSilent(spec.silent)
            .setCategory(NotificationCompat.CATEGORY_STATUS);

        // Fresh one-shot nonces replace the ones of any earlier version of this notification.
        store.clearNonces(spec.id);
        Intent body = buildOpenIntent(context, bodyUri(spec.id));
        if (body != null) {
            // Tapping the body just opens the app. It carries no extras and triggers no event.
            b.setContentIntent(pending(context, body));
            for (NotificationSpec.Action a : spec.actions) {
                Intent i = buildOpenIntent(context, actionUri(spec.id, a.id));
                String nonce = Guards.newNonce(RANDOM);
                store.putNonce(spec.id, a.id, nonce);
                i.putExtra(EXTRA_NOTIFICATION_ID, spec.id);
                i.putExtra(EXTRA_ACTION_ID, a.id);
                i.putExtra(EXTRA_DATA, spec.dataJson);
                i.putExtra(EXTRA_NONCE, nonce);
                b.addAction(0, a.title, pending(context, i));
            }
        }
        try {
            nm.notify(spec.id, numericId, b.build());
        } catch (SecurityException e) {
            Log.w(TAG, "Not allowed to post notification", e);
            return false;
        }
        store.saveNotification(spec.id, spec.raw);
        return true;
    }

    /**
     * Posts the last version of a notification again with fresh nonces, after an action was
     * consumed, so that its buttons keep working. Does nothing when the user has dismissed it.
     */
    static void refresh(Context context, PushStore store, String notificationId) {
        String raw = store.getNotification(notificationId);
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (raw == null || nm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        boolean active = false;
        int numericId = NotificationSpec.numericId(notificationId);
        for (StatusBarNotification n : nm.getActiveNotifications()) {
            if (n.getId() == numericId && notificationId.equals(n.getTag())) {
                active = true;
            }
        }
        if (!active) {
            return;
        }
        try {
            NotificationSpec spec = NotificationSpec.parse(new JSONObject().put("notification", new JSONObject(raw)));
            if (spec != null && !spec.cancel) {
                handle(context, spec, store);
            }
        } catch (JSONException e) {
            Log.w(TAG, "Could not refresh notification", e);
        }
    }

    /**
     * Uses the requested channel when it exists (the app creates its channels), otherwise the
     * plugin's default channel. A server can therefore not make the app create channels.
     */
    private static String resolveChannel(Context context, NotificationManager nm, String requested) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return DEFAULT_CHANNEL_ID;
        }
        if (requested != null && nm.getNotificationChannel(requested) != null) {
            return requested;
        }
        if (requested != null) {
            Log.w(TAG, "Channel '" + requested + "' does not exist, using the default channel");
        }
        if (nm.getNotificationChannel(DEFAULT_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                new NotificationChannel(
                    DEFAULT_CHANNEL_ID,
                    context.getString(R.string.unifiedpush_default_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            );
        }
        return DEFAULT_CHANNEL_ID;
    }

    /**
     * Explicit intent for the app's launcher activity. It deliberately is not the plain
     * MAIN/LAUNCHER intent: Android resumes the existing task for that one and drops the extras.
     * The data URI makes every PendingIntent distinct for {@code Intent.filterEquals}.
     */
    private static Intent buildOpenIntent(Context context, String uri) {
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (launch == null || launch.getComponent() == null) {
            return null;
        }
        Intent i = new Intent(INTENT_ACTION);
        i.setComponent(launch.getComponent());
        i.setData(Uri.parse(uri));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return i;
    }

    private static PendingIntent pending(Context context, Intent intent) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(context, 0, intent, flags);
    }

    /** Data URI of the body tap intent of a notification. */
    static String bodyUri(String notificationId) {
        return "capacitorunifiedpush://n/" + encode(notificationId) + "/body";
    }

    /** Data URI of an action button intent. Distinct per notification id and action id. */
    static String actionUri(String notificationId, String actionId) {
        return "capacitorunifiedpush://n/" + encode(notificationId) + "/a/" + encode(actionId);
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
