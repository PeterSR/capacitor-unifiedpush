package dev.capacitorunifiedpush;

import android.util.Log;
import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;
import org.unifiedpush.android.connector.FailedReason;
import org.unifiedpush.android.connector.PushService;
import org.unifiedpush.android.connector.data.PublicKeySet;
import org.unifiedpush.android.connector.data.PushEndpoint;
import org.unifiedpush.android.connector.data.PushMessage;

/**
 * Receives UnifiedPush events from the connector library, also while the app is not running.
 * Handles the native notification, then hands everything to the plugin (or buffers it).
 */
public class UnifiedPushService extends PushService {

    @Override
    public void onNewEndpoint(PushEndpoint endpoint, String instance) {
        JSONObject json = new JSONObject();
        try {
            json.put("instance", instance);
            json.put("endpoint", endpoint.getUrl());
            PublicKeySet keys = endpoint.getPubKeySet();
            if (keys != null) {
                JSONObject k = new JSONObject();
                k.put("p256dh", keys.getPubKey());
                k.put("auth", keys.getAuth());
                json.put("pubKeySet", k);
            } else {
                json.put("pubKeySet", JSONObject.NULL);
            }
            json.put("temporary", endpoint.getTemporary());
        } catch (JSONException e) {
            Log.e(NotificationHandler.TAG, "Could not encode endpoint", e);
            return;
        }
        UnifiedPushPlugin.dispatchEndpoint(this, instance, json);
    }

    @Override
    public void onMessage(PushMessage message, String instance) {
        String text = new String(message.getContent(), StandardCharsets.UTF_8);
        if (!message.getDecrypted()) {
            // Never fall back to plaintext: a message that was not decrypted with our keys is
            // dropped. It is not shown and not passed to JS.
            Log.d(NotificationHandler.TAG, "Dropping message that is not Web Push encrypted (instance " + instance + ")");
            return;
        }
        JSONObject json = tryParseObject(text);

        NotificationSpec spec = NotificationSpec.parse(json);
        if (spec != null) {
            applyNotification(spec);
        }
        String id = UnifiedPushPlugin.dispatchMessage(this, instance, text);
        if (id != null) {
            MessageBroadcast.send(this, instance, text, id);
        }
    }

    private void applyNotification(NotificationSpec spec) {
        PushStore store = new PushStore(this);
        synchronized (PushStore.LOCK) {
            long now = System.currentTimeMillis();
            if (!Guards.acceptTimestamp(store.getTimestamp(spec.id), spec.timestamp, now)) {
                Log.d(NotificationHandler.TAG, "Ignoring stale or implausible notification operation for " + spec.id);
                return;
            }
            try {
                // Record the timestamp only once the operation really was applied.
                if (NotificationHandler.handle(this, spec, store) && spec.timestamp != null) {
                    store.recordTimestamp(spec.id, spec.timestamp);
                }
            } catch (RuntimeException e) {
                Log.e(NotificationHandler.TAG, "Failed to handle notification", e);
            }
        }
    }

    @Override
    public void onRegistrationFailed(FailedReason reason, String instance) {
        UnifiedPushPlugin.dispatchRegistrationFailed(this, instance, reason.name());
    }

    @Override
    public void onUnregistered(String instance) {
        UnifiedPushPlugin.dispatchUnregistered(this, instance);
    }

    @Override
    public void onTempUnavailable(String instance) {
        Log.d(NotificationHandler.TAG, "Distributor temporarily unavailable for instance " + instance);
    }

    static JSONObject tryParseObject(String text) {
        String trimmed = text.trim();
        if (!trimmed.startsWith("{")) {
            return null;
        }
        try {
            return new JSONObject(trimmed);
        } catch (JSONException e) {
            return null;
        }
    }
}
