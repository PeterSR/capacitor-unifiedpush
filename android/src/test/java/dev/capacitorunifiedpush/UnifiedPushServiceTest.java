package dev.capacitorunifiedpush;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Intent;
import androidx.test.core.app.ApplicationProvider;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.unifiedpush.android.connector.data.PushMessage;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class UnifiedPushServiceTest {

    private final Application app = ApplicationProvider.getApplicationContext();

    private UnifiedPushService service() {
        return Robolectric.setupService(UnifiedPushService.class);
    }

    private static PushMessage message(String text, boolean decrypted) {
        return new PushMessage(text.getBytes(StandardCharsets.UTF_8), decrypted);
    }

    @Test
    public void undecryptableMessageIsNotBroadcast() {
        service().onMessage(message("{\"a\":1}", false), "default");
        assertTrue(Shadows.shadowOf(app).getBroadcastIntents().isEmpty());
        assertEquals(0, new PushStore(app).listItems(PushStore.QUEUE_MESSAGES).length());
    }

    @Test
    public void acceptedMessageIsStoredThenBroadcastOnceWithStoredId() throws Exception {
        service().onMessage(message("{\"a\":1}", true), "default");
        JSONArray stored = new PushStore(app).listItems(PushStore.QUEUE_MESSAGES);
        assertEquals(1, stored.length());
        List<Intent> sent = Shadows.shadowOf(app).getBroadcastIntents();
        assertEquals(1, sent.size());
        Intent i = sent.get(0);
        assertEquals(MessageBroadcast.ACTION, i.getAction());
        assertEquals(app.getPackageName(), i.getPackage());
        assertEquals("default", i.getStringExtra("instance"));
        assertEquals("{\"a\":1}", i.getStringExtra("data"));
        assertEquals(stored.getJSONObject(0).getString("id"), i.getStringExtra("id"));
    }
}
