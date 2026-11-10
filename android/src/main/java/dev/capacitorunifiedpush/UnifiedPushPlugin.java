package dev.capacitorunifiedpush;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import androidx.core.app.NotificationManagerCompat;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import kotlin.Unit;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.unifiedpush.android.connector.ConstantsKt;
import org.unifiedpush.android.connector.UnifiedPush;

@CapacitorPlugin(
    name = "UnifiedPush",
    permissions = { @Permission(strings = { Manifest.permission.POST_NOTIFICATIONS }, alias = "notifications") }
)
public class UnifiedPushPlugin extends Plugin {

    private static volatile UnifiedPushPlugin current;

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void load() {
        current = this;
        if (getActivity() != null) {
            handleIntent(getActivity().getIntent());
        }
    }

    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void handleOnDestroy() {
        if (current == this) {
            current = null;
        }
        super.handleOnDestroy();
    }

    /** Turns a tapped notification action into a queued, delivered event. See {@link ActionIntentHandler}. */
    private void handleIntent(Intent intent) {
        synchronized (PushStore.LOCK) {
            JSONObject stored = ActionIntentHandler.process(getContext(), new PushStore(getContext()), intent);
            if (stored != null && hasListeners("notificationAction")) {
                notifyListeners("notificationAction", toJSObject(stored));
            }
        }
    }

    @Override
    @PluginMethod(returnType = PluginMethod.RETURN_NONE)
    public void addListener(PluginCall call) {
        synchronized (PushStore.LOCK) {
            super.addListener(call);
            String event = call.getString("eventName");
            // Redeliver everything not acknowledged yet, also after a WebView reload.
            if ("message".equals(event)) {
                redeliver(PushStore.QUEUE_MESSAGES, "message");
            } else if ("notificationAction".equals(event)) {
                redeliver(PushStore.QUEUE_ACTIONS, "notificationAction");
            } else if ("unregistered".equals(event)) {
                redeliver(PushStore.QUEUE_UNREGISTERED, "unregistered");
            }
        }
    }

    @Override
    @PluginMethod(returnType = PluginMethod.RETURN_NONE)
    public void removeListener(PluginCall call) {
        synchronized (PushStore.LOCK) {
            super.removeListener(call);
        }
    }

    @Override
    @PluginMethod(returnType = PluginMethod.RETURN_PROMISE)
    public void removeAllListeners(PluginCall call) {
        synchronized (PushStore.LOCK) {
            super.removeAllListeners(call);
        }
    }

    private void redeliver(String queue, String event) {
        JSONArray items = new PushStore(getContext()).listItems(queue);
        for (int i = 0; i < items.length(); i++) {
            JSONObject m = items.optJSONObject(i);
            if (m != null) {
                notifyListeners(event, toJSObject(forEmit(queue, m)));
            }
        }
    }

    /** Messages are stored with the raw payload only. The parsed object is added when emitting. */
    static JSONObject forEmit(String queue, JSONObject stored) {
        if (!PushStore.QUEUE_MESSAGES.equals(queue)) {
            return stored;
        }
        try {
            JSONObject copy = new JSONObject(stored.toString());
            JSONObject json = UnifiedPushService.tryParseObject(stored.optString("data", ""));
            if (json != null) {
                copy.put("json", json);
            }
            return copy;
        } catch (JSONException e) {
            return stored;
        }
    }

    // ------------------------------------------------------- dispatch from service

    static void dispatchEndpoint(Context context, String instance, JSONObject endpoint) {
        new PushStore(context).saveEndpoint(instance, endpoint);
        emit("endpoint", endpoint);
    }

    /** Queued like messages, so an app that was not running still learns which endpoint to delete. */
    static void dispatchUnregistered(Context context, String instance) {
        PushStore store = new PushStore(context);
        JSONObject o = new JSONObject();
        synchronized (PushStore.LOCK) {
            JSONObject last = store.getEndpoint(instance);
            store.removeEndpoint(instance);
            try {
                o.put("instance", instance);
                o.put("endpoint", last == null ? JSONObject.NULL : last.opt("endpoint"));
                o.put("pubKeySet", last == null ? JSONObject.NULL : last.opt("pubKeySet"));
            } catch (JSONException e) {
                return;
            }
            JSONObject stored = store.addItem(PushStore.QUEUE_UNREGISTERED, o);
            UnifiedPushPlugin p = current;
            if (p != null && p.hasListeners("unregistered")) {
                p.notifyListeners("unregistered", toJSObject(stored));
            }
        }
    }

    static void dispatchRegistrationFailed(Context context, String instance, String reason) {
        JSONObject o = new JSONObject();
        try {
            o.put("instance", instance);
            o.put("reason", reason);
        } catch (JSONException ignored) {}
        emit("registrationFailed", o);
    }

    /**
     * Stores the message until JS acknowledges it, and delivers it now when a listener is
     * attached. Delivery is at least once.
     */
    static void dispatchMessage(Context context, String instance, String data) {
        JSONObject m = new JSONObject();
        try {
            m.put("instance", instance);
            m.put("data", data);
        } catch (JSONException e) {
            return;
        }
        synchronized (PushStore.LOCK) {
            JSONObject stored = new PushStore(context).addItem(PushStore.QUEUE_MESSAGES, m);
            UnifiedPushPlugin p = current;
            if (p != null && p.hasListeners("message")) {
                p.notifyListeners("message", toJSObject(forEmit(PushStore.QUEUE_MESSAGES, stored)));
            }
        }
    }

    private static void emit(String event, JSONObject payload) {
        UnifiedPushPlugin p = current;
        if (p != null) {
            p.notifyListeners(event, toJSObject(payload));
        }
    }

    private static JSObject toJSObject(JSONObject o) {
        try {
            return new JSObject(o.toString());
        } catch (JSONException e) {
            return new JSObject();
        }
    }

    // ------------------------------------------------------------ plugin methods

    @PluginMethod
    public void getDistributors(PluginCall call) {
        JSArray arr = new JSArray();
        for (String d : UnifiedPush.getDistributors(getContext())) {
            arr.put(d);
        }
        JSObject ret = new JSObject();
        ret.put("distributors", arr);
        call.resolve(ret);
    }

    @PluginMethod
    public void getSavedDistributor(PluginCall call) {
        JSObject ret = new JSObject();
        String saved = UnifiedPush.getSavedDistributor(getContext());
        ret.put("distributor", saved == null ? JSONObject.NULL : saved);
        call.resolve(ret);
    }

    @PluginMethod
    public void saveDistributor(PluginCall call) {
        String distributor = call.getString("distributor");
        if (distributor == null || distributor.isEmpty()) {
            call.reject("distributor is required", "INVALID_ARGUMENT");
            return;
        }
        if (!UnifiedPush.getDistributors(getContext()).contains(distributor)) {
            call.reject("Distributor is not installed: " + distributor, "UNKNOWN_DISTRIBUTOR");
            return;
        }
        UnifiedPush.saveDistributor(getContext(), distributor);
        call.resolve();
    }

    @PluginMethod
    public void register(PluginCall call) {
        final String instance = instanceOf(call);
        final String messageForDistributor = call.getString("messageForDistributor");
        final String vapid = call.getString("vapid");
        final Context context = getContext();

        if (getActivity() == null) {
            call.reject("No activity available", "NO_ACTIVITY");
            return;
        }
        // The connector may start a system picker activity, which must happen on the UI thread.
        getActivity().runOnUiThread(() -> {
            try {
                String saved = UnifiedPush.getSavedDistributor(context);
                if (saved != null) {
                    doRegister(call, instance, messageForDistributor, vapid, saved);
                    return;
                }
                UnifiedPush.tryUseCurrentOrDefaultDistributor(getActivity(), (Boolean ok) -> {
                    try {
                        String distributor = ok ? UnifiedPush.getSavedDistributor(context) : null;
                        if (distributor != null) {
                            doRegister(call, instance, messageForDistributor, vapid, distributor);
                        } else if (UnifiedPush.getDistributors(context).isEmpty()) {
                            call.reject("No UnifiedPush distributor is installed", "NO_DISTRIBUTOR");
                        } else {
                            call.reject("No distributor was selected", "NO_DISTRIBUTOR_SELECTED");
                        }
                    } catch (RuntimeException e) {
                        call.reject("Registration failed: " + e.getMessage(), "REGISTER_FAILED", e);
                    }
                    return Unit.INSTANCE;
                });
            } catch (RuntimeException e) {
                call.reject("Registration failed: " + e.getMessage(), "REGISTER_FAILED", e);
            }
        });
    }

    private void doRegister(PluginCall call, String instance, String message, String vapid, String distributor) {
        try {
            UnifiedPush.register(getContext(), instance, message, vapid);
        } catch (Exception e) {
            // The connector throws VapidNotValidException (a checked Kotlin exception) for bad keys.
            String code = e instanceof UnifiedPush.VapidNotValidException ? "INVALID_VAPID" : "REGISTER_FAILED";
            call.reject("Registration failed: " + e.getMessage(), code, e);
            return;
        }
        JSObject ret = new JSObject();
        ret.put("distributor", distributor);
        call.resolve(ret);
    }

    @PluginMethod
    public void unregister(PluginCall call) {
        String instance = instanceOf(call);
        PushStore store = new PushStore(getContext());
        JSONObject last = store.getEndpoint(instance);
        UnifiedPush.unregister(getContext(), instance);
        store.removeEndpoint(instance);
        JSObject ret = new JSObject();
        // The endpoint that was just dropped, so the app can delete it on its server.
        ret.put("endpoint", last == null ? JSONObject.NULL : last.opt("endpoint"));
        call.resolve(ret);
    }

    @PluginMethod
    public void getLastEndpoint(PluginCall call) {
        String instance = instanceOf(call);
        JSONObject stored = new PushStore(getContext()).getEndpoint(instance);
        JSObject ret = new JSObject();
        ret.put("instance", instance);
        if (stored == null) {
            ret.put("endpoint", JSONObject.NULL);
            ret.put("pubKeySet", JSONObject.NULL);
        } else {
            ret.put("endpoint", stored.optString("endpoint", null));
            ret.put("pubKeySet", stored.opt("pubKeySet"));
            ret.put("temporary", stored.optBoolean("temporary", false));
        }
        call.resolve(ret);
    }

    @PluginMethod
    public void getPendingMessages(PluginCall call) {
        resolveQueue(call, PushStore.QUEUE_MESSAGES, "messages");
    }

    @PluginMethod
    public void ackMessages(PluginCall call) {
        ack(call, PushStore.QUEUE_MESSAGES);
    }

    @PluginMethod
    public void getPendingActions(PluginCall call) {
        resolveQueue(call, PushStore.QUEUE_ACTIONS, "actions");
    }

    @PluginMethod
    public void ackActions(PluginCall call) {
        ack(call, PushStore.QUEUE_ACTIONS);
    }

    @PluginMethod
    public void getPendingUnregistrations(PluginCall call) {
        resolveQueue(call, PushStore.QUEUE_UNREGISTERED, "unregistrations");
    }

    @PluginMethod
    public void ackUnregistrations(PluginCall call) {
        ack(call, PushStore.QUEUE_UNREGISTERED);
    }

    private void resolveQueue(PluginCall call, String queue, String key) {
        JSObject ret = new JSObject();
        try {
            JSONArray items = new PushStore(getContext()).listItems(queue);
            JSONArray out = new JSONArray();
            for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.optJSONObject(i);
                if (o != null) {
                    out.put(forEmit(queue, o));
                }
            }
            ret.put(key, new JSArray(out.toString()));
        } catch (JSONException e) {
            ret.put(key, new JSArray());
        }
        call.resolve(ret);
    }

    private void ack(PluginCall call, String queue) {
        JSArray ids = call.getArray("ids");
        if (ids == null) {
            call.reject("ids is required", "INVALID_ARGUMENT");
            return;
        }
        java.util.List<String> list = new java.util.ArrayList<>();
        for (int i = 0; i < ids.length(); i++) {
            String id = ids.optString(i, null);
            if (id != null) {
                list.add(id);
            }
        }
        new PushStore(getContext()).ackItems(queue, list);
        call.resolve();
    }

    private static String instanceOf(PluginCall call) {
        String instance = call.getString("instance");
        return instance == null || instance.isEmpty() ? ConstantsKt.INSTANCE_DEFAULT : instance;
    }

    // --------------------------------------------------------------- permissions

    @PluginMethod
    @Override
    public void checkPermissions(PluginCall call) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            resolveLegacyPermission(call);
        } else {
            super.checkPermissions(call);
        }
    }

    @PluginMethod
    @Override
    public void requestPermissions(PluginCall call) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            resolveLegacyPermission(call);
        } else {
            super.requestPermissions(call);
        }
    }

    /** Below Android 13 there is no runtime permission, but the user can still switch notifications off. */
    private void resolveLegacyPermission(PluginCall call) {
        boolean enabled = NotificationManagerCompat.from(getContext()).areNotificationsEnabled();
        JSObject ret = new JSObject();
        ret.put("notifications", enabled ? "granted" : "denied");
        call.resolve(ret);
    }
}
