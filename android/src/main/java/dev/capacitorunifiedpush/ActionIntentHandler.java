package dev.capacitorunifiedpush;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Validates a notification action tap that arrives as an intent.
 *
 * <p>The launcher activity is exported, so any app could send an intent with these extras, and
 * Android keeps the last intent as the task's base intent (replayed from Recents or after process
 * death). An action is therefore only accepted when it carries the one-shot nonce the plugin
 * stored when it posted the notification, and never when launched from history.
 */
final class ActionIntentHandler {

    private ActionIntentHandler() {}

    /**
     * Consumes the nonce, queues the action and refreshes the notification. Call while holding
     * {@link PushStore#LOCK}.
     *
     * @return the queued item, or null when the intent is not an accepted action tap
     */
    static JSONObject process(Context context, PushStore store, Intent intent) {
        if (intent == null || !NotificationHandler.INTENT_ACTION.equals(intent.getAction())) {
            return null;
        }
        String actionId = intent.getStringExtra(NotificationHandler.EXTRA_ACTION_ID);
        String notificationId = intent.getStringExtra(NotificationHandler.EXTRA_NOTIFICATION_ID);
        String dataJson = intent.getStringExtra(NotificationHandler.EXTRA_DATA);
        String nonce = intent.getStringExtra(NotificationHandler.EXTRA_NONCE);
        boolean fromHistory = (intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0;
        // Removing the extras only affects our copy, the nonce is what makes a replay harmless.
        intent.removeExtra(NotificationHandler.EXTRA_ACTION_ID);
        intent.removeExtra(NotificationHandler.EXTRA_NOTIFICATION_ID);
        intent.removeExtra(NotificationHandler.EXTRA_DATA);
        intent.removeExtra(NotificationHandler.EXTRA_NONCE);
        if (actionId == null || notificationId == null || fromHistory) {
            return null;
        }
        if (!store.consumeNonce(notificationId, actionId, nonce)) {
            Log.d(NotificationHandler.TAG, "Ignoring notification action without a valid one-shot nonce");
            return null;
        }
        JSONObject item = new JSONObject();
        try {
            item.put("notificationId", notificationId);
            item.put("actionId", actionId);
            item.put("data", dataJson == null ? new JSONObject() : new JSONObject(dataJson));
        } catch (JSONException e) {
            return null;
        }
        JSONObject stored = store.addItem(PushStore.QUEUE_ACTIONS, item);
        // The nonce is spent, post the notification again so its buttons keep working.
        NotificationHandler.refresh(context, store, notificationId);
        return stored;
    }
}
