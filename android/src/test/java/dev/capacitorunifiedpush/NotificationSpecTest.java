package dev.capacitorunifiedpush;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

// Robolectric provides a working org.json.
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class NotificationSpecTest {

    @Test
    public void parsesFullNotification() throws Exception {
        JSONObject msg = new JSONObject(
            "{\"type\":\"example.state\",\"notification\":{\"id\":\"example:1\",\"title\":\"Running\","
                + "\"body\":\"Since 10:00\",\"ongoing\":true,\"silent\":true,\"channel_id\":\"timers\","
                + "\"actions\":[{\"id\":\"pause\",\"title\":\"Pause\"},{\"id\":\"stop\",\"title\":\"Stop\"}],"
                + "\"data\":{\"item_id\":\"1\"}}}"
        );
        NotificationSpec s = NotificationSpec.parse(msg);
        assertEquals("example:1", s.id);
        assertEquals("Running", s.title);
        assertTrue(s.ongoing);
        assertTrue(s.silent);
        assertFalse(s.cancel);
        assertEquals("timers", s.channelId);
        assertEquals(2, s.actions.size());
        assertEquals("pause", s.actions.get(0).id);
        assertEquals("1", new JSONObject(s.dataJson).getString("item_id"));
    }

    @Test
    public void parsesCancel() throws Exception {
        NotificationSpec s = NotificationSpec.parse(
            new JSONObject("{\"notification\":{\"id\":\"example:1\",\"cancel\":true}}")
        );
        assertTrue(s.cancel);
        assertEquals("{}", s.dataJson);
        assertNull(s.channelId);
    }

    @Test
    public void ignoresMessagesWithoutUsableNotification() throws Exception {
        assertNull(NotificationSpec.parse(null));
        assertNull(NotificationSpec.parse(new JSONObject("{\"type\":\"x\"}")));
        assertNull(NotificationSpec.parse(new JSONObject("{\"notification\":\"nope\"}")));
        assertNull(NotificationSpec.parse(new JSONObject("{\"notification\":{\"title\":\"no id\"}}")));
    }

    @Test
    public void limitsAndFiltersActions() throws Exception {
        NotificationSpec s = NotificationSpec.parse(
            new JSONObject(
                "{\"notification\":{\"id\":\"a\",\"title\":\"t\",\"actions\":["
                    + "{\"id\":\"1\",\"title\":\"One\"},{\"id\":\"\",\"title\":\"Bad\"},{\"id\":\"2\"},"
                    + "{\"id\":\"3\",\"title\":\"Three\"},{\"id\":\"4\",\"title\":\"Four\"},{\"id\":\"5\",\"title\":\"Five\"}]}}"
            )
        );
        assertEquals(3, s.actions.size());
        assertEquals("3", s.actions.get(1).id);
        assertEquals("4", s.actions.get(2).id);
    }

    @Test
    public void numericIdIsStableAndDistinct() {
        assertEquals(NotificationSpec.numericId("example:1"), NotificationSpec.numericId("example:1"));
        assertNotEquals(NotificationSpec.numericId("example:1"), NotificationSpec.numericId("example:2"));
    }

    @Test
    public void bufferKeepsLastN() throws Exception {
        JSONArray buf = new JSONArray();
        for (int i = 0; i < 25; i++) {
            buf = MessageBuffer.append(buf, new JSONObject().put("n", i), 20);
        }
        assertEquals(20, buf.length());
        assertEquals(5, buf.getJSONObject(0).getInt("n"));
        assertEquals(24, buf.getJSONObject(19).getInt("n"));
    }

    @Test
    public void bufferParseToleratesGarbage() {
        assertEquals(0, MessageBuffer.parse(null).length());
        assertEquals(0, MessageBuffer.parse("not json").length());
    }

    @Test
    public void parsesTimestamp() throws Exception {
        NotificationSpec s = NotificationSpec.parse(
            new JSONObject("{\"notification\":{\"id\":\"a\",\"title\":\"t\",\"timestamp\":1700000000000}}")
        );
        assertEquals(Long.valueOf(1700000000000L), s.timestamp);
        assertNull(NotificationSpec.parse(new JSONObject("{\"notification\":{\"id\":\"a\",\"title\":\"t\"}}")).timestamp);
    }

    @Test
    public void nonceMatchesOnlyIdentical() {
        String n = Guards.newNonce(new java.security.SecureRandom());
        assertEquals(32, n.length());
        assertNotEquals(n, Guards.newNonce(new java.security.SecureRandom()));
        assertTrue(Guards.nonceMatches(n, n));
        assertFalse(Guards.nonceMatches(n, n + "0"));
        assertFalse(Guards.nonceMatches(n, null));
        assertFalse(Guards.nonceMatches(null, n));
        assertFalse(Guards.nonceMatches(null, null));
    }

    @Test
    public void timestampGuard() {
        long now = 1_000_000_000_000L;
        assertTrue(Guards.acceptTimestamp(null, null, now));
        assertTrue(Guards.acceptTimestamp(500L, null, now));
        assertTrue(Guards.acceptTimestamp(null, now, now));
        assertTrue(Guards.acceptTimestamp(now - 10, now, now));
        assertFalse(Guards.acceptTimestamp(now, now, now));
        assertFalse(Guards.acceptTimestamp(now, now - 1, now));
        assertTrue(Guards.acceptTimestamp(null, now + Guards.MAX_FUTURE_MS, now));
        assertFalse(Guards.acceptTimestamp(null, now + Guards.MAX_FUTURE_MS + 1, now));
    }

    @Test
    public void dataUrisAreDistinctAndEncoded() {
        String a = NotificationHandler.actionUri("n:1", "stop");
        assertEquals("capacitorunifiedpush://n/n%3A1/a/stop", a);
        assertNotEquals(a, NotificationHandler.actionUri("n:1", "pause"));
        assertNotEquals(a, NotificationHandler.actionUri("n:2", "stop"));
        assertNotEquals(a, NotificationHandler.bodyUri("n:1"));
        assertNotEquals(NotificationHandler.actionUri("a/a/b", "c"), NotificationHandler.actionUri("a", "b/c"));
    }

    @Test
    public void pruneAndRemove() throws Exception {
        JSONArray arr = new JSONArray()
            .put(new JSONObject().put("id", "old").put("receivedAt", 100L))
            .put(new JSONObject().put("id", "a").put("receivedAt", 900L))
            .put(new JSONObject().put("id", "b").put("receivedAt", 950L));
        JSONArray pruned = MessageBuffer.prune(arr, 1000L, 200L);
        assertEquals(2, pruned.length());
        JSONArray rest = MessageBuffer.remove(pruned, new java.util.HashSet<>(java.util.Arrays.asList("a")));
        assertEquals(1, rest.length());
        assertEquals("b", rest.getJSONObject(0).getString("id"));
    }
}
