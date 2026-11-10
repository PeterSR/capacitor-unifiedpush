package dev.capacitorunifiedpush;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import androidx.test.core.app.ApplicationProvider;
import java.util.Collections;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PushStoreTest {

    private Context context;
    private PushStore store;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("dev.capacitorunifiedpush", Context.MODE_PRIVATE).edit().clear().commit();
        store = new PushStore(context);
    }

    @Test
    public void nonceIsConsumedOnce() {
        store.putNonce("n:1", "stop", "abc");
        assertFalse(store.consumeNonce("n:1", "stop", "wrong"));
        assertFalse(store.consumeNonce("n:1", "pause", "abc"));
        assertTrue(store.consumeNonce("n:1", "stop", "abc"));
        assertFalse(store.consumeNonce("n:1", "stop", "abc"));
    }

    @Test
    public void nonceKeysCannotBeForgedThroughIds() {
        store.putNonce("a|b", "c", "x");
        assertFalse(store.consumeNonce("a", "b|c", "x"));
        assertTrue(store.consumeNonce("a|b", "c", "x"));
    }

    @Test
    public void clearNoncesRemovesAllActionsOfOneNotification() {
        store.putNonce("n:1", "a", "1");
        store.putNonce("n:1", "b", "2");
        store.putNonce("n:2", "a", "3");
        store.clearNonces("n:1");
        assertFalse(store.consumeNonce("n:1", "a", "1"));
        assertFalse(store.consumeNonce("n:1", "b", "2"));
        assertTrue(store.consumeNonce("n:2", "a", "3"));
    }

    @Test
    public void timestampIsRecordedAndOldEntriesPruned() {
        assertNull(store.getTimestamp("n:1"));
        store.recordTimestamp("n:1", 1234L);
        assertEquals(Long.valueOf(1234L), store.getTimestamp("n:1"));
        // An entry recorded long ago is pruned by the next write.
        long longAgo = System.currentTimeMillis() - PushStore.TIMESTAMP_RETENTION_MS - 1000;
        context.getSharedPreferences("dev.capacitorunifiedpush", Context.MODE_PRIVATE)
            .edit().putString("ts:old", "99," + longAgo).commit();
        store.recordTimestamp("n:2", 5L);
        assertNull(store.getTimestamp("old"));
        assertEquals(Long.valueOf(1234L), store.getTimestamp("n:1"));
    }

    @Test
    public void staleNotificationRecordsAndTheirNoncesArePruned() {
        store.saveNotification("n:old", "{\"id\":\"n:old\"}");
        store.putNonce("n:old", "a", "1");
        // Age the record.
        long longAgo = System.currentTimeMillis() - PushStore.TIMESTAMP_RETENTION_MS - 1000;
        context.getSharedPreferences("dev.capacitorunifiedpush", Context.MODE_PRIVATE)
            .edit().putString("notification:n%3Aold", "{\"at\":" + longAgo + ",\"raw\":\"{}\"}").commit();
        store.saveNotification("n:new", "{\"id\":\"n:new\"}");
        assertNull(store.getNotification("n:old"));
        assertFalse(store.consumeNonce("n:old", "a", "1"));
        assertEquals("{\"id\":\"n:new\"}", store.getNotification("n:new"));
    }

    @Test
    public void repostOfStaleNotificationKeepsFreshNonces() {
        long longAgo = System.currentTimeMillis() - PushStore.TIMESTAMP_RETENTION_MS - 1000;
        context.getSharedPreferences("dev.capacitorunifiedpush", Context.MODE_PRIVATE)
            .edit().putString("notification:n%3A1", "{\"at\":" + longAgo + ",\"raw\":\"{}\"}").commit();
        // Same order as NotificationHandler.handle: clear, put fresh nonces, then save the record.
        store.clearNonces("n:1");
        store.putNonce("n:1", "stop", "fresh");
        store.saveNotification("n:1", "{\"id\":\"n:1\"}");
        assertEquals("{\"id\":\"n:1\"}", store.getNotification("n:1"));
        assertTrue(store.consumeNonce("n:1", "stop", "fresh"));
    }

    @Test
    public void malformedRecordKeyDoesNotBreakSaving() {
        context.getSharedPreferences("dev.capacitorunifiedpush", Context.MODE_PRIVATE)
            .edit().putString("notification:%zz", "{\"at\":1,\"raw\":\"{}\"}").commit();
        store.saveNotification("n:1", "{}");
        assertEquals("{}", store.getNotification("n:1"));
    }

    @Test
    public void queueKeepsLastFiftyAckAndRedelivery() throws Exception {
        for (int i = 0; i < 55; i++) {
            store.addItem(PushStore.QUEUE_MESSAGES, new JSONObject().put("n", i));
        }
        JSONArray items = store.listItems(PushStore.QUEUE_MESSAGES);
        assertEquals(50, items.length());
        assertEquals(5, items.getJSONObject(0).getInt("n"));
        assertTrue(items.getJSONObject(0).has("id"));
        // Listing again (redelivery) returns the same unacknowledged items.
        assertEquals(50, store.listItems(PushStore.QUEUE_MESSAGES).length());
        String first = items.getJSONObject(0).getString("id");
        store.ackItems(PushStore.QUEUE_MESSAGES, Collections.singletonList(first));
        JSONArray after = store.listItems(PushStore.QUEUE_MESSAGES);
        assertEquals(49, after.length());
        assertEquals(6, after.getJSONObject(0).getInt("n"));
    }

    @Test
    public void expiredQueueItemsAreNotListed() throws Exception {
        store.addItem(PushStore.QUEUE_MESSAGES, new JSONObject().put("n", 1));
        long old = System.currentTimeMillis() - PushStore.MAX_AGE_MS - 1000;
        JSONArray arr = new JSONArray().put(new JSONObject().put("id", "x").put("receivedAt", old));
        context.getSharedPreferences("dev.capacitorunifiedpush", Context.MODE_PRIVATE)
            .edit().putString("queue:messages", arr.toString()).commit();
        assertEquals(0, store.listItems(PushStore.QUEUE_MESSAGES).length());
    }

    // -- action intents

    private Intent actionIntent(String notificationId, String actionId, String nonce) {
        Intent i = new Intent(NotificationHandler.INTENT_ACTION);
        i.putExtra(NotificationHandler.EXTRA_NOTIFICATION_ID, notificationId);
        i.putExtra(NotificationHandler.EXTRA_ACTION_ID, actionId);
        i.putExtra(NotificationHandler.EXTRA_DATA, "{\"item_id\":\"7\"}");
        if (nonce != null) {
            i.putExtra(NotificationHandler.EXTRA_NONCE, nonce);
        }
        return i;
    }

    @Test
    public void validActionIsAcceptedExactlyOnce() throws Exception {
        store.putNonce("n:1", "stop", "secret");
        JSONObject item = ActionIntentHandler.process(context, store, actionIntent("n:1", "stop", "secret"));
        assertEquals("stop", item.getString("actionId"));
        assertEquals("n:1", item.getString("notificationId"));
        assertEquals("7", item.getJSONObject("data").getString("item_id"));
        assertEquals(1, store.listItems(PushStore.QUEUE_ACTIONS).length());
        // Replaying the same intent (for example from Recents) is rejected.
        assertNull(ActionIntentHandler.process(context, store, actionIntent("n:1", "stop", "secret")));
        assertEquals(1, store.listItems(PushStore.QUEUE_ACTIONS).length());
    }

    @Test
    public void actionLaunchedFromHistoryIsRejected() {
        store.putNonce("n:1", "stop", "secret");
        Intent i = actionIntent("n:1", "stop", "secret");
        i.addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY);
        assertNull(ActionIntentHandler.process(context, store, i));
        assertEquals(0, store.listItems(PushStore.QUEUE_ACTIONS).length());
        // The nonce was not spent, a genuine tap still works.
        assertTrue(ActionIntentHandler.process(context, store, actionIntent("n:1", "stop", "secret")) != null);
    }

    @Test
    public void spoofedActionsAreRejected() {
        store.putNonce("n:1", "stop", "secret");
        assertNull(ActionIntentHandler.process(context, store, actionIntent("n:1", "stop", null)));
        assertNull(ActionIntentHandler.process(context, store, actionIntent("n:1", "stop", "guess")));
        assertNull(ActionIntentHandler.process(context, store, actionIntent("n:9", "stop", "secret")));
        assertNull(ActionIntentHandler.process(context, store, actionIntent("n:1", "other", "secret")));
        Intent wrongAction = actionIntent("n:1", "stop", "secret");
        wrongAction.setAction("android.intent.action.MAIN");
        assertNull(ActionIntentHandler.process(context, store, wrongAction));
        assertEquals(0, store.listItems(PushStore.QUEUE_ACTIONS).length());
    }
}
