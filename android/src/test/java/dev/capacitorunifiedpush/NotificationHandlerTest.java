package dev.capacitorunifiedpush;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class NotificationHandlerTest {

    private final Application app = ApplicationProvider.getApplicationContext();

    private Notification post(String extra) throws Exception {
        NotificationManager manager = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        // An existing channel avoids the plugin's default channel, whose name needs app resources.
        manager.createNotificationChannel(
            new NotificationChannel("test", "Test", NotificationManager.IMPORTANCE_DEFAULT)
        );
        NotificationSpec spec = NotificationSpec.parse(
            new JSONObject(
                "{\"notification\":{\"id\":\"n1\",\"title\":\"T\",\"body\":\"B\",\"channel_id\":\"test\"" + extra + "}}"
            )
        );
        NotificationHandler.handle(app, spec, new PushStore(app));
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        Notification n = Shadows.shadowOf(nm).getNotification("n1", NotificationSpec.numericId("n1"));
        assertNotNull(n);
        return n;
    }

    @Test
    public void appliesColorAndFallsBackWhenIconIsMissing() throws Exception {
        Notification n = post(",\"icon\":\"ic_stat_missing\",\"color\":\"#1E88E5\"");
        assertEquals(0xFF1E88E5, n.color);
        assertEquals(R.drawable.ic_stat_unifiedpush, n.getSmallIcon().getResId());
    }

    @Test
    public void colorSurvivesRefresh() throws Exception {
        post(",\"color\":\"#1E88E5\"");
        PushStore store = new PushStore(app);
        NotificationHandler.refresh(app, store, "n1");
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        Notification n = Shadows.shadowOf(nm).getNotification("n1", NotificationSpec.numericId("n1"));
        assertEquals(0xFF1E88E5, n.color);
    }
}
