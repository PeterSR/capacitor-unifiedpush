package dev.capacitorunifiedpush;

import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Bounded, expiring list of items (oldest first) with an "id" and a "receivedAt" each.
 * Pure logic so it can be unit tested.
 */
final class MessageBuffer {

    private MessageBuffer() {}

    /** Returns a new array with {@code item} appended, keeping only the last {@code max} entries. */
    static JSONArray append(JSONArray current, JSONObject item, int max) {
        JSONArray all = new JSONArray();
        for (int i = 0; i < current.length(); i++) {
            all.put(current.opt(i));
        }
        all.put(item);
        int start = Math.max(0, all.length() - max);
        JSONArray out = new JSONArray();
        for (int i = start; i < all.length(); i++) {
            out.put(all.opt(i));
        }
        return out;
    }

    /** Drops items received more than {@code maxAgeMs} before {@code now}. */
    static JSONArray prune(JSONArray current, long now, long maxAgeMs) {
        JSONArray out = new JSONArray();
        for (int i = 0; i < current.length(); i++) {
            JSONObject o = current.optJSONObject(i);
            if (o != null && now - o.optLong("receivedAt", 0) <= maxAgeMs) {
                out.put(o);
            }
        }
        return out;
    }

    /** Drops the items whose id is in {@code ids}. */
    static JSONArray remove(JSONArray current, Set<String> ids) {
        JSONArray out = new JSONArray();
        for (int i = 0; i < current.length(); i++) {
            JSONObject o = current.optJSONObject(i);
            if (o != null && !ids.contains(o.optString("id"))) {
                out.put(o);
            }
        }
        return out;
    }

    static JSONArray parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new JSONArray();
        }
        try {
            return new JSONArray(raw);
        } catch (JSONException e) {
            return new JSONArray();
        }
    }
}
