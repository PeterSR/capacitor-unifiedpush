# capacitor-unifiedpush

A [Capacitor](https://capacitorjs.com) plugin for [UnifiedPush](https://unifiedpush.org) on Android.
It lets a Capacitor app receive push messages without Firebase or Google Play Services, through a
self-hostable distributor, and it can show notifications natively even when the app is not running.

It wraps the official [UnifiedPush Android connector](https://codeberg.org/UnifiedPush/android-connector)
(`org.unifiedpush.android:connector` 3.3.5) and uses its Web Push support: the device gets an endpoint
URL plus an encryption key set, your server posts RFC 8291 encrypted messages to that URL, and the
connector decrypts them on the device.

- Android only. On the web and on iOS every method rejects as unimplemented.
- Capacitor 6, 7 and 8 (`@capacitor/core ^6 || ^7 || ^8`).
- Android `minSdk` follows your Capacitor version (22 for 6, 23 for 7, 24 for 8).

## What is UnifiedPush

UnifiedPush is an open protocol for delivering push messages. The user installs a **distributor** app
(for example ntfy, NextPush, or Conversations) that keeps one connection to a push server of their
choice. Apps register with the distributor and receive an **endpoint**, a URL. Your application server
sends an HTTP POST to that URL and the distributor wakes the app and hands over the message. See
[unifiedpush.org](https://unifiedpush.org) for distributors and server details.

## Install

```sh
npm install capacitor-unifiedpush
npx cap sync android
```

The plugin's Android library brings in the connector, declares the push service and the
`POST_NOTIFICATIONS` permission, and needs no code in your app.

## Android setup

1. Nothing to add to `AndroidManifest.xml`. The plugin manifest registers its push service and merges
   into yours.
2. Your Gradle setup must be able to resolve `org.unifiedpush.android:connector` from Maven Central
   (the default `mavenCentral()` repository).
3. Optional: provide your own small notification icon as `res/drawable/ic_stat_unifiedpush.xml` in
   your app to override the plugin's bell icon. It must be a white, transparent-background icon. To
   pick an icon per message, see `icon` in the notification schema.
4. Optional: create your own notification channels in your app (for example with
   `NotificationManager.createNotificationChannel` in your main activity, or a channel plugin). A message
   can name one with `channel_id`. If that channel does not exist, the plugin falls back to its default
   channel and never creates channels on a server's say so.
5. Optional but recommended: exclude the plugin's stored state from Android backups, see
   "Backup" below.
6. Android 13 and newer: ask for the notification permission once, with `requestPermissions()`.

### Minimal usage

```ts
import { UnifiedPush } from 'capacitor-unifiedpush';

// Attach listeners first, then register.
await UnifiedPush.addListener('endpoint', async ({ instance, endpoint, pubKeySet }) => {
  // Send these to your server: it needs all three to push to this device.
  await fetch('https://app.example.org/api/push-subscription', {
    method: 'PUT',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ instance, endpoint, keys: pubKeySet }),
  });
});
await UnifiedPush.addListener('registrationFailed', ({ reason }) => console.warn(reason));
await UnifiedPush.addListener('message', async ({ id, data, json }) => {
  // Delivery is at least once: deduplicate by id, then acknowledge.
  console.log(json ?? data);
  await UnifiedPush.ackMessages({ ids: [id] });
});
await UnifiedPush.addListener('notificationAction', async ({ id, notificationId, actionId, data }) => {
  // The user tapped an action button; the app has been opened. Authenticate as usual, then act.
  await UnifiedPush.ackActions({ ids: [id] });
});

await UnifiedPush.requestPermissions();

const { distributors } = await UnifiedPush.getDistributors();
if (distributors.length === 0) {
  // Tell the user to install a UnifiedPush distributor, for example ntfy.
} else {
  // Optional: let the user choose, then UnifiedPush.saveDistributor({ distributor }).
  // Without it, register() uses the user's default distributor (the OS may show a picker).
  await UnifiedPush.register({ messageForDistributor: 'Example app' });
}

// On every app start, resync your server with what the plugin knows:
const last = await UnifiedPush.getLastEndpoint();
if (last.endpoint) { /* compare with what the server has */ }
```

Call `register()` on every app start (it is cheap) so a removed distributor is noticed.

## API reference

All types are in `src/definitions.ts` and exported from the package.

### Methods

| Method | Description |
| --- | --- |
| `getDistributors(): Promise<{ distributors: string[] }>` | Package names of installed distributors. |
| `getSavedDistributor(): Promise<{ distributor: string \| null }>` | The saved distributor. |
| `saveDistributor({ distributor })` | Saves the distributor to use. Rejects with `UNKNOWN_DISTRIBUTOR` if not installed. |
| `register({ instance?, messageForDistributor?, vapid? }): Promise<{ distributor: string }>` | Registers with the saved distributor, or with the user's current or default one when none is saved (and saves it). Resolves when the request is sent. The endpoint arrives as an `endpoint` event. |
| `unregister({ instance? }): Promise<{ endpoint: string \| null }>` | Unregisters and forgets the stored endpoint. Resolves with the dropped endpoint so you can delete it on your server. |
| `getLastEndpoint({ instance? }): Promise<{ instance, endpoint: string \| null, pubKeySet: {p256dh, auth} \| null, temporary? }>` | The last endpoint stored by the plugin, to resync after a restart. |
| `getPendingMessages(): Promise<{ messages }>` / `ackMessages({ ids })` | Unacknowledged messages, and acknowledging them. |
| `getPendingUnregistrations(): Promise<{ unregistrations }>` / `ackUnregistrations({ ids })` | Unacknowledged `unregistered` events, so an app that was not running still learns which endpoint to delete. |
| `getPendingActions(): Promise<{ actions }>` / `ackActions({ ids })` | Unacknowledged notification action taps, and acknowledging them. |
| `checkPermissions()` / `requestPermissions()` | `{ notifications: 'granted' \| 'denied' \| 'prompt' \| 'prompt-with-rationale' }`. Below Android 13 there is no prompt: `granted` unless notifications are switched off for the app, then `denied`. |

`instance` defaults to `"default"`. Use other values to hold several registrations.
`vapid` is the application server's VAPID public key (base64url, 87 characters), needed by some
distributors; a malformed key rejects with `INVALID_VAPID`.

Rejection codes: `NO_DISTRIBUTOR` (none installed), `NO_DISTRIBUTOR_SELECTED` (several
installed and none chosen), `INVALID_VAPID`, `REGISTER_FAILED`, `NO_ACTIVITY`, `INVALID_ARGUMENT`,
`UNKNOWN_DISTRIBUTOR`.

### Events (`addListener`)

| Event | Payload |
| --- | --- |
| `endpoint` | `{ instance, endpoint, pubKeySet: { p256dh, auth } \| null, temporary? }`. Keys are base64url without padding. |
| `registrationFailed` | `{ instance, reason }` with reason `INTERNAL_ERROR`, `NETWORK`, `ACTION_REQUIRED` or `VAPID_REQUIRED`. |
| `unregistered` | `{ id, receivedAt, instance, endpoint, pubKeySet }`. Stored and redelivered like `message`. The distributor removed the registration. `endpoint` is the last known endpoint (null if unknown), so you can delete it on your server. |
| `message` | `{ id, receivedAt, instance, data, json? }` (`PushMessageEvent`). `data` is the decrypted payload as UTF-8, `json` is set when it is a JSON object. |
| `notificationAction` | `{ id, receivedAt, notificationId, actionId, data }`. |

The `endpoint` and `registrationFailed` events are only delivered while JS is running. Use
`getLastEndpoint()` on start to resync.

### Delivery guarantee (messages, action taps and unregistrations)

Delivery is **at least once**, and nothing is lost to a WebView reload or a cold start.

- Every decrypted message, every accepted action tap and every unregistration is first stored in
  SharedPreferences with a unique `id` and a `receivedAt` time. At most the **last 50** of each are
  kept, for at most **7 days**.
- If a listener is attached, the event is also emitted right away.
- Each time a `message`, `notificationAction` or `unregistered` listener attaches, all
  unacknowledged items are emitted again, oldest first. `getPendingMessages()`,
  `getPendingActions()` and `getPendingUnregistrations()` return the same list on demand.
- An item stays until you call `ackMessages({ ids })`, `ackActions({ ids })` or
  `ackUnregistrations({ ids })`. In Capacitor 6 native listeners survive a WebView reload while
  their JS callbacks are gone; Capacitor 7 and 8 clear them. Either way an emitted event is not
  proof that your code handled it, and redelivery on the next attach covers both. Acknowledge after
  handling, and deduplicate by `id`.
- If you never acknowledge, you will see the same items again on every listener attach until they
  expire or are pushed out by newer ones.

## Native message broadcast (Android)

For every accepted message (decrypted, stored for JS delivery) the plugin sends a package-restricted broadcast
so that your own native code, for example a home screen widget's `BroadcastReceiver`, can react while
the WebView is not running. Messages that fail decryption are never broadcast.

| | |
| --- | --- |
| Action | `dev.capacitorunifiedpush.MESSAGE` |
| Restricted to | your own app, through `intent.setPackage(packageName)` |
| Extra `instance` | String, the registration instance |
| Extra `data` | String, the decrypted payload as UTF-8 |
| Extra `id` | String, the id the message was stored with (the `id` of the `message` event) |

The broadcast is on by default. To switch it off, add this to the `<application>` element of your
manifest (this keeps things simple: no runtime API, the choice is made at build time). The literal boolean `false` is the supported form; the string "false" and the integer 0 are also treated as off:

```xml
<meta-data android:name="dev.capacitorunifiedpush.broadcast_messages" android:value="false" />
```

Minimal receiver (Kotlin):

```kotlin
class PushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val instance = intent.getStringExtra("instance")
        val data = intent.getStringExtra("data") ?: return
        val id = intent.getStringExtra("id")
        // Keep this short. Use goAsync() or enqueue WorkManager for anything longer.
        // For example parse `data` and refresh an app widget.
    }
}
```

```xml
<receiver android:name=".PushReceiver" android:exported="false">
    <intent-filter>
        <action android:name="dev.capacitorunifiedpush.MESSAGE" />
    </intent-filter>
</receiver>
```

A receiver registered at runtime must use `ContextCompat.registerReceiver(..., ContextCompat.RECEIVER_NOT_EXPORTED)`.

Security: the broadcast is limited to your package, and the receiver must be `android:exported="false"`
so no other app can send it forged messages. The payload is already decrypted, so do not log it or
forward it outside the app. The broadcast is sent in addition to the stored `message` event, which stays
the reliable path: a broadcast is best effort and is not redelivered, so treat it as a hint to refresh
and use the `message` event (with its acknowledgement) for anything that must not be lost.

## Notification schema

The plugin is generic. Its only fixed schema is an optional top-level `notification` object in the
message JSON. When present, the plugin shows, updates or cancels a native notification, even when the
app is not running. The whole message is still passed to JS as a `message` event.

```json
{
  "type": "example.state",
  "state": { "any": "app data" },
  "notification": {
    "id": "timer:42",
    "title": "Running",
    "body": "Since 23:10",
    "ongoing": true,
    "actions": [{ "id": "pause", "title": "Pause" }, { "id": "stop", "title": "Stop" }],
    "data": { "timer_id": "42" },
    "channel_id": "timers",
    "silent": false,
    "icon": "ic_stat_timer",
    "color": "#1E88E5",
    "timestamp": 1760000000000
  }
}
```

Cancel:

```json
{ "notification": { "id": "timer:42", "cancel": true, "timestamp": 1760000060000 } }
```

| Field | Type | Meaning |
| --- | --- | --- |
| `id` | string, required | Identifies the notification. The same id updates it in place; the plugin derives the Android notification id from it. |
| `title`, `body` | string | Content. At least one is needed to show. |
| `ongoing` | boolean | Marks the notification as ongoing, so it is not dismissed by a normal swipe. Default false. Android 14 and newer let users swipe such notifications away anyway. |
| `actions` | `[{ id, title }]` | Up to 3 buttons. Tapping one opens the app and queues a `notificationAction`. The notification stays until you cancel or update it with another push. |
| `data` | object | Returned in `notificationAction`. |
| `channel_id` | string | Notification channel created by your app. The plugin's default channel is used when absent or when the channel does not exist. |
| `silent` | boolean | No sound or vibration. Updates to an existing notification never alert again. |
| `icon` | string, optional | Name of a drawable resource in your app, for example `ic_stat_timer`. Must match `^ic_stat_[a-z0-9_]{1,56}$` (the `ic_stat_` prefix is required, so a message cannot pick arbitrary app drawables). Falls back to the default icon when the name is invalid or the drawable is missing or cannot be loaded. See "Small icons" below. |
| `color` | string, optional | Accent colour as `#RRGGBB` (six hex digits, nothing else). Applied to the small icon and action area. Ignored when it does not match exactly. |
| `cancel` | boolean | Remove the notification. |
| `timestamp` | integer, optional | Milliseconds since epoch, set by the sender. See below. |

Tapping the body opens the app without an event.

The notification intents carry a data URI with the scheme `capacitorunifiedpush://n/...` (distinct per
notification and action). After a cold start from a notification, `App.getLaunchUrl()` from
`@capacitor/app` returns that URI. Ignore URLs with this scheme in your deep link handling.

`icon` and `color` are stored with the notification record, so the notification keeps them when the
plugin re-posts it after an action tap.

### Small icons

Android requires the small notification icon to be monochrome: white shapes on a transparent
background. Colour in the icon is discarded by the system and usually renders as a plain white or grey
square, so use `color` for accent instead. To add icons, put vector drawables (or PNGs per density) in
your app's `android/app/src/main/res/drawable/`, for example `ic_stat_timer.xml`, and send
`"icon": "ic_stat_timer"`. Name lookup is by resource name in your app package at runtime. If you
use resource shrinking, keep the drawables, for example with a `res/raw/keep.xml` containing
`<resources xmlns:tools="http://schemas.android.com/tools" tools:keep="@drawable/ic_stat_*" />`.

### Timestamp guard

The plugin stores the last applied `timestamp` per notification `id` and ignores any show, update or
cancel whose `timestamp` is older than or equal to the stored one. This stops replayed or out of order
messages from resurrecting a stale notification.

- The timestamp is recorded only after the operation was actually applied (a notification that was not
  shown because notifications are disabled does not block a later one).
- A `timestamp` more than 24 hours in the future is rejected, so one bad value cannot block an id
  forever.
- Stored timestamps are pruned after 30 days.
- Without a `timestamp` the operation is always applied.

Use a monotonically increasing value per id, such as the server's time in milliseconds when the state
changed. The check applies to the native notification only; JS still receives the message.

## Server side: minimal Web Push example

Your server needs the `endpoint` URL and the `p256dh` and `auth` keys reported by the `endpoint`
event, and encrypts with RFC 8291 (`aes128gcm`). With Python and
[`pywebpush`](https://pypi.org/project/pywebpush/):

```python
import json, time
from pywebpush import webpush

subscription = {
    "endpoint": "https://push.example.org/UP?token=abc123",   # from the endpoint event
    "keys": {"p256dh": "<p256dh>", "auth": "<auth>"},          # from pubKeySet
}
message = {
    "type": "example.state",
    "notification": {
        "id": "timer:42", "title": "Running", "body": "Since 23:10",
        "ongoing": True, "timestamp": int(time.time() * 1000),
        "actions": [{"id": "stop", "title": "Stop"}],
        "data": {"timer_id": "42"},
    },
}
webpush(subscription_info=subscription, data=json.dumps(message), ttl=60)
# Add vapid_private_key and vapid_claims if the distributor requires VAPID, and pass the
# matching public key as `vapid` to register().
```

Any RFC 8291 library works. Do not send plaintext: it is dropped (see below).

## Security notes

- **Plaintext is never accepted.** A message that fails Web Push decryption, or that arrives
  unencrypted although the registration has keys, is dropped and logged at debug level. It is never
  shown as a notification and never passed to JS. Anyone who learns an endpoint URL can post to it, so
  only messages encrypted to the device's own keys are trusted to carry content.
- **Treat the endpoint and keys as secrets** on your server.
- **The push channel carries no authority.** Notification actions only open the app, which then
  authenticates itself to your backend before doing anything. Do not make a push message, or a tap on
  its button, perform a privileged operation by itself. Use `timestamp` to limit replay of display
  state, but not as an authorization mechanism.
- **Action taps are protected against replay and spoofing.** The app's launcher activity is exported,
  so another app could send it an intent that looks like an action tap, and Android keeps the last
  launch intent so that it can be replayed from Recents or after the process was killed. When the
  plugin posts a notification it therefore generates a random one-shot token for every action button,
  stores it, and puts it in that button's intent. A tap is accepted only if the token matches and is
  deleted on use; intents launched from the recents list, or without a valid token, are ignored. After
  a tap the plugin re-posts the notification (if still shown) with fresh tokens so the buttons keep
  working. Tapping the body carries no data and only opens the app.
- The plugin keeps endpoints, stored messages (up to 50, 7 days), action taps, tokens and
  notification timestamps in the app's private SharedPreferences file `dev.capacitorunifiedpush`.
  Stored messages are plaintext on disk until acknowledged or expired. Notification records and their
  tokens that were not updated for 30 days are pruned (so action buttons of a notification older than
  that stop working until it is updated).

### Backup

Exclude the plugin's file and the connector's own stores from Android Auto Backup and device
transfer. The connector keeps the private Web Push decryption keys of every registration in its
database. If that database (or the plugin's endpoint records) is restored onto another device, the
distributor there knows nothing about it: the registration is dead and every message pushed to it
is undecryptable. Excluding them forces a clean registration after a restore. In `AndroidManifest.xml` of your app:

```xml
<application
    android:dataExtractionRules="@xml/data_extraction_rules"
    android:fullBackupContent="@xml/backup_rules">
```

`res/xml/data_extraction_rules.xml` (Android 12 and newer):

```xml
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="sharedpref" path="dev.capacitorunifiedpush.xml" />
        <exclude domain="sharedpref" path="unifiedpush.connector.xml" />
        <exclude domain="database" path="unifiedpush-connector" />
    </cloud-backup>
    <device-transfer>
        <exclude domain="sharedpref" path="dev.capacitorunifiedpush.xml" />
        <exclude domain="sharedpref" path="unifiedpush.connector.xml" />
        <exclude domain="database" path="unifiedpush-connector" />
    </device-transfer>
</data-extraction-rules>
```

`res/xml/backup_rules.xml` (Android 11 and older):

```xml
<full-backup-content>
    <exclude domain="sharedpref" path="dev.capacitorunifiedpush.xml" />
    <exclude domain="sharedpref" path="unifiedpush.connector.xml" />
    <exclude domain="database" path="unifiedpush-connector" />
</full-backup-content>
```

## Limitations

- Android only. There is no iOS implementation (UnifiedPush has no iOS client); calls reject as
  unimplemented.
- A distributor app must be installed. The plugin reports `NO_DISTRIBUTOR` but does not install one.
- At most 3 action buttons per notification (an Android display limit).
- Action taps open the app; they do not run code in the background.
- Messages that are not Web Push encrypted are dropped.
- Notification action buttons are single use between pushes: the plugin refreshes the notification
  after a tap, but a button tapped twice within a moment may ignore the second tap.
- Delivery to JS is at least once, not exactly once. Deduplicate by `id`.
- Delivery timing and reliability depend on the distributor and the device's battery settings.
- Android 13 and newer need the notification permission, otherwise notifications are not shown
  (messages still reach JS).
- Kotlin: the plugin itself is Java, and the connector is an `implementation` dependency, so Java-only
  apps are not affected. An app that applies its own Kotlin Gradle plugin older than 2.1 can fail to
  read the connector's Kotlin metadata (the connector is built with Kotlin 2.2 and Kotlin 2.1 language
  settings); use Kotlin 2.1 or newer there. Apps on Android Gradle Plugin 8.2 may see harmless D8
  warnings about Kotlin metadata.
- Minification: release builds with R8 or ProGuard are **untested**. The connector 3.3.5 AAR ships an
  empty `proguard.txt` and this plugin adds no rules. If a minified build misbehaves, start with:

  ```
  -keep class org.unifiedpush.android.connector.** { *; }
  -keep class com.google.crypto.tink.** { *; }
  -keep class com.google.protobuf.** { *; }
  -dontwarn com.google.errorprone.annotations.**
  -dontwarn javax.annotation.**
  ```
- Size: the connector brings the Tink crypto library and protobuf-java, about 5 MB added to the
  APK before shrinking (Tink 2.8 MB, protobuf-java 1.9 MB, gson 0.3 MB). If your app also uses `protobuf-javalite` (for example through Firebase or
  Google Play services), the two protobuf flavours clash with duplicate class errors; exclude one of
  them in Gradle.

## License

MIT, see `LICENSE`. Third party material is listed in `NOTICE`:

- The notification icon is the Material Design "notifications" icon, Apache License 2.0.
- The UnifiedPush Android connector (`org.unifiedpush.android:connector`) is Apache License 2.0. It
  bundles a patched fork of the Tink Web Push classes and depends on Tink (Apache License 2.0), which
  in turn depends on protobuf-java (BSD 3-Clause), gson, jsr305 and error_prone_annotations, and on
  kotlin-stdlib. Their licences are listed one by one in `NOTICE`; most are Apache License 2.0, and
  protobuf-java and part of jsr305 are not.

The Apache License 2.0 text is in `LICENSES/Apache-2.0.txt`.
