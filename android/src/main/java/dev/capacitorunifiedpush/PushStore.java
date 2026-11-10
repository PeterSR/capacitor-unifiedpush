package dev.capacitorunifiedpush;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * SharedPreferences backed store: last endpoint per instance, undelivered messages and actions,
 * one-shot nonces, applied timestamps and the last notification per id.
 *
 * <p>All access is serialised on {@link #LOCK}, which the plugin shares so that the push service
 * (any thread) and the plugin's listener handling see a consistent state. Writes that must
 * survive the process being killed right after a push use {@code commit()}: queues, nonces
 * (a consumed nonce must stay consumed), timestamps, endpoints and notification records. Only the
 * removal of an endpoint or of a notification record uses {@code apply()}, because losing one only
 * leaves a harmless stale entry that is pruned later.
 */
final class PushStore {

    static final Object LOCK = new Object();

    static final String QUEUE_MESSAGES = "messages";
    static final String QUEUE_ACTIONS = "actions";
    static final String QUEUE_UNREGISTERED = "unregistered";
    static final int MAX_ITEMS = 50;
    static final long MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000;
    static final long TIMESTAMP_RETENTION_MS = 30L * 24 * 60 * 60 * 1000;

    private static final String PREFS = "dev.capacitorunifiedpush";
    private static final String KEY_ENDPOINT = "endpoint:";
    private static final String KEY_QUEUE = "queue:";
    private static final String KEY_NONCE = "nonce:";
    private static final String KEY_TS = "ts:";
    private static final String KEY_NOTIFICATION = "notification:";

    private final SharedPreferences prefs;

    PushStore(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- endpoints

    void saveEndpoint(String instance, JSONObject endpoint) {
        synchronized (LOCK) {
            prefs.edit().putString(KEY_ENDPOINT + instance, endpoint.toString()).commit();
        }
    }

    JSONObject getEndpoint(String instance) {
        synchronized (LOCK) {
            String raw = prefs.getString(KEY_ENDPOINT + instance, null);
            if (raw == null) {
                return null;
            }
            try {
                return new JSONObject(raw);
            } catch (JSONException e) {
                return null;
            }
        }
    }

    void removeEndpoint(String instance) {
        synchronized (LOCK) {
            prefs.edit().remove(KEY_ENDPOINT + instance).apply();
        }
    }

    // ------------------------------------------------------------------- queues

    /** Appends an item with a fresh id and receive time, bounded by count and age. Returns the stored item. */
    JSONObject addItem(String queue, JSONObject item) {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            try {
                item.put("id", UUID.randomUUID().toString());
                item.put("receivedAt", now);
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
            JSONArray next = MessageBuffer.append(
                MessageBuffer.prune(load(queue), now, MAX_AGE_MS),
                item,
                MAX_ITEMS
            );
            prefs.edit().putString(KEY_QUEUE + queue, next.toString()).commit();
            return item;
        }
    }

    /** All unacknowledged, unexpired items, oldest first. */
    JSONArray listItems(String queue) {
        synchronized (LOCK) {
            return MessageBuffer.prune(load(queue), System.currentTimeMillis(), MAX_AGE_MS);
        }
    }

    void ackItems(String queue, Collection<String> ids) {
        synchronized (LOCK) {
            JSONArray next = MessageBuffer.remove(load(queue), new HashSet<>(ids));
            prefs.edit().putString(KEY_QUEUE + queue, next.toString()).commit();
        }
    }

    private JSONArray load(String queue) {
        return MessageBuffer.parse(prefs.getString(KEY_QUEUE + queue, null));
    }

    // ------------------------------------------------------------------- nonces

    /** Ids are URL encoded so that the "|" separator can not be forged by an id. */
    static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String decode(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String noncePrefix(String notificationId) {
        return KEY_NONCE + enc(notificationId) + "|";
    }

    private static String nonceKey(String notificationId, String actionId) {
        return noncePrefix(notificationId) + enc(actionId);
    }

    void putNonce(String notificationId, String actionId, String nonce) {
        synchronized (LOCK) {
            prefs.edit().putString(nonceKey(notificationId, actionId), nonce).commit();
        }
    }

    /** Accepts the nonce once: a matching one is deleted, anything else is refused. */
    boolean consumeNonce(String notificationId, String actionId, String presented) {
        synchronized (LOCK) {
            String key = nonceKey(notificationId, actionId);
            if (!Guards.nonceMatches(prefs.getString(key, null), presented)) {
                return false;
            }
            prefs.edit().remove(key).commit();
            return true;
        }
    }

    private void removeNoncesInto(SharedPreferences.Editor e, String notificationId) {
        String prefix = noncePrefix(notificationId);
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(prefix)) {
                e.remove(key);
            }
        }
    }

    void clearNonces(String notificationId) {
        synchronized (LOCK) {
            SharedPreferences.Editor e = prefs.edit();
            removeNoncesInto(e, notificationId);
            e.commit();
        }
    }

    // ------------------------------------------------- notifications, timestamps

    /**
     * Remembers the last version of a notification (to refresh it after an action tap). Records
     * not updated for 30 days are dropped together with their nonces.
     */
    void saveNotification(String id, String raw) {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            SharedPreferences.Editor e = prefs.edit();
            String ownKey = KEY_NOTIFICATION + enc(id);
            for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                String key = entry.getKey();
                // The record being written now is refreshed below, and its fresh nonces must stay.
                if (key.equals(ownKey)) {
                    continue;
                }
                if (key.startsWith(KEY_NOTIFICATION) && entry.getValue() instanceof String) {
                    long at = 0;
                    try {
                        at = new JSONObject((String) entry.getValue()).optLong("at", 0);
                    } catch (JSONException ex) {
                        // unreadable record, treated as old
                    }
                    if (now - at > TIMESTAMP_RETENTION_MS) {
                        e.remove(key);
                        try {
                            removeNoncesInto(e, decode(key.substring(KEY_NOTIFICATION.length())));
                        } catch (IllegalArgumentException bad) {
                            // malformed key (not written by this plugin): the record is removed,
                            // its nonces cannot be located
                        }
                    }
                }
            }
            try {
                e.putString(ownKey, new JSONObject().put("at", now).put("raw", raw).toString());
            } catch (JSONException ex) {
                throw new IllegalStateException(ex);
            }
            e.commit();
        }
    }

    String getNotification(String id) {
        synchronized (LOCK) {
            String rec = prefs.getString(KEY_NOTIFICATION + enc(id), null);
            if (rec == null) {
                return null;
            }
            try {
                return new JSONObject(rec).optString("raw", null);
            } catch (JSONException e) {
                return null;
            }
        }
    }

    void removeNotification(String id) {
        synchronized (LOCK) {
            prefs.edit().remove(KEY_NOTIFICATION + enc(id)).apply();
        }
    }

    /** Last applied timestamp for a notification id, or null. */
    Long getTimestamp(String id) {
        synchronized (LOCK) {
            String raw = prefs.getString(KEY_TS + id, null);
            if (raw == null) {
                return null;
            }
            try {
                return Long.valueOf(raw.split(",")[0]);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    /** Records an applied timestamp and prunes entries recorded more than 30 days ago. */
    void recordTimestamp(String id, long timestamp) {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            SharedPreferences.Editor e = prefs.edit();
            for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                if (entry.getKey().startsWith(KEY_TS) && entry.getValue() instanceof String) {
                    String[] parts = ((String) entry.getValue()).split(",");
                    try {
                        if (parts.length == 2 && now - Long.parseLong(parts[1]) > TIMESTAMP_RETENTION_MS) {
                            e.remove(entry.getKey());
                        }
                    } catch (NumberFormatException ex) {
                        e.remove(entry.getKey());
                    }
                }
            }
            e.putString(KEY_TS + id, timestamp + "," + now);
            e.commit();
        }
    }
}
