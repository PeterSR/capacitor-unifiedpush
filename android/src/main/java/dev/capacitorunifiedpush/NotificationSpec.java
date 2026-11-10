package dev.capacitorunifiedpush;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The optional top level {@code notification} object of a push message.
 *
 * <pre>
 * { "notification": { "id": "...", "title": "...", "body": "...", "ongoing": true,
 *   "actions": [{"id": "...", "title": "..."}], "data": {}, "channel_id": "...", "silent": false, "timestamp": 1700000000000,
 *   "icon": "ic_stat_example", "color": "#1E88E5" } }
 * { "notification": { "id": "...", "cancel": true } }
 * </pre>
 */
final class NotificationSpec {

    static final int MAX_ACTIONS = 3;

    /** Android resource name rules, lower case only. */
    private static final Pattern ICON_NAME = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");
    private static final Pattern COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    static final class Action {

        final String id;
        final String title;

        Action(String id, String title) {
            this.id = id;
            this.title = title;
        }
    }

    final String id;
    final boolean cancel;
    final String title;
    final String body;
    final boolean ongoing;
    final boolean silent;
    final String channelId;
    final List<Action> actions;
    /** Name of a drawable in the app for the small icon, or null when absent or invalid. */
    final String icon;
    /** Accent colour as ARGB int (opaque), or null when absent or invalid. */
    final Integer color;
    /** The {@code data} object serialised as JSON, or "{}" when absent. */
    final String dataJson;
    /** Sender timestamp in ms since epoch, or null when absent. */
    final Long timestamp;
    /** The notification object as received, kept so the notification can be re-posted. */
    final String raw;

    private NotificationSpec(
        String id,
        boolean cancel,
        String title,
        String body,
        boolean ongoing,
        boolean silent,
        String channelId,
        List<Action> actions,
        String icon,
        Integer color,
        String dataJson,
        Long timestamp,
        String raw
    ) {
        this.id = id;
        this.cancel = cancel;
        this.title = title;
        this.body = body;
        this.ongoing = ongoing;
        this.silent = silent;
        this.channelId = channelId;
        this.actions = actions;
        this.icon = icon;
        this.color = color;
        this.dataJson = dataJson;
        this.timestamp = timestamp;
        this.raw = raw;
    }

    /** Android notification id derived from the string id. Stable across process restarts. */
    static int numericId(String id) {
        return id.hashCode();
    }

    /**
     * Parses the {@code notification} object out of a decoded message.
     *
     * @return null when the message has no usable notification object (absent, not an object,
     *     or without a non empty string id)
     */
    static NotificationSpec parse(JSONObject message) {
        if (message == null) {
            return null;
        }
        JSONObject n = message.optJSONObject("notification");
        if (n == null) {
            return null;
        }
        String id = n.optString("id", "");
        if (id.isEmpty()) {
            return null;
        }
        List<Action> actions = new ArrayList<>();
        JSONArray rawActions = n.optJSONArray("actions");
        if (rawActions != null) {
            for (int i = 0; i < rawActions.length() && actions.size() < MAX_ACTIONS; i++) {
                JSONObject a = rawActions.optJSONObject(i);
                if (a == null) {
                    continue;
                }
                String actionId = a.optString("id", "");
                String actionTitle = a.optString("title", "");
                if (!actionId.isEmpty() && !actionTitle.isEmpty()) {
                    actions.add(new Action(actionId, actionTitle));
                }
            }
        }
        JSONObject data = n.optJSONObject("data");
        String channel = n.optString("channel_id", "");
        return new NotificationSpec(
            id,
            n.optBoolean("cancel", false),
            n.optString("title", ""),
            n.optString("body", ""),
            n.optBoolean("ongoing", false),
            n.optBoolean("silent", false),
            channel.isEmpty() ? null : channel,
            actions,
            validIcon(n.optString("icon", "")),
            parseColor(n.optString("color", "")),
            data == null ? "{}" : data.toString(),
            n.has("timestamp") && !n.isNull("timestamp") ? Long.valueOf(n.optLong("timestamp")) : null,
            n.toString()
        );
    }

    /** Returns the name when it is a valid Android drawable resource name, otherwise null. */
    static String validIcon(String name) {
        return name != null && ICON_NAME.matcher(name).matches() ? name : null;
    }

    /** Parses "#RRGGBB" into an opaque ARGB int, or returns null for anything else. */
    static Integer parseColor(String value) {
        if (value == null || !COLOR.matcher(value).matches()) {
            return null;
        }
        return Integer.valueOf(0xFF000000 | Integer.parseInt(value.substring(1), 16));
    }
}
