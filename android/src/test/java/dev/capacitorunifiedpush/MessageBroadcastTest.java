package dev.capacitorunifiedpush;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import androidx.test.core.app.ApplicationProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MessageBroadcastTest {

    private final Application app = ApplicationProvider.getApplicationContext();

    @Test
    public void buildsPackageRestrictedIntentWithExtras() {
        Intent i = MessageBroadcast.build(app, "default", "{\"a\":1}", "id-1");
        assertEquals("dev.capacitorunifiedpush.MESSAGE", i.getAction());
        assertEquals(app.getPackageName(), i.getPackage());
        assertEquals("default", i.getStringExtra("instance"));
        assertEquals("{\"a\":1}", i.getStringExtra("data"));
        assertEquals("id-1", i.getStringExtra("id"));
    }

    @Test
    public void sendsByDefault() {
        MessageBroadcast.send(app, "default", "x", "id-2");
        Intent sent = Shadows.shadowOf(app).getBroadcastIntents().get(0);
        assertEquals(app.getPackageName(), sent.getPackage());
        assertEquals("id-2", sent.getStringExtra("id"));
    }

    @Test
    public void metaDataFalseSwitchesItOff() {
        ApplicationInfo info = app.getApplicationInfo();
        if (info.metaData == null) {
            info.metaData = new android.os.Bundle();
        }
        info.metaData.putBoolean(MessageBroadcast.META_ENABLED, false);
        assertTrue(!MessageBroadcast.isEnabled(app));
        MessageBroadcast.send(app, "default", "x", "id-3");
        assertTrue(Shadows.shadowOf(app).getBroadcastIntents().isEmpty());
    }
}
