# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## Unreleased

## 0.1.0 - 2026-11-10

### Added

- Android implementation on top of the UnifiedPush connector 3.3.5.
- `getDistributors`, `getSavedDistributor`, `saveDistributor`, `register`, `unregister` (resolves the
  dropped endpoint), `getLastEndpoint`, `checkPermissions`, `requestPermissions`.
- Events: `endpoint`, `registrationFailed`, `unregistered` (with the last known endpoint), `message`,
  `notificationAction`.
- Native handling of an optional `notification` object in the message (show, update, cancel, ongoing,
  action buttons, channel, silent), also while the app is not running.
- At-least-once delivery of messages, action taps and unregistrations: stored (last 50, 7 days) until
  acknowledged, redelivered on listener attach, with `getPending*` and `ack*` methods.
- Action taps protected by a one-shot nonce per PendingIntent, ignored when launched from history or
  spoofed.
- Replay and staleness guard through an optional `notification.timestamp`: recorded only for applied
  operations, rejects timestamps more than 24 hours ahead, prunes old entries.
- Messages that are not Web Push encrypted to the device are dropped.
- Web implementation that rejects as unimplemented. No iOS implementation.
- Unit tests (Robolectric) for the store, nonce, timestamp and action intent logic.
- CI matrix for Capacitor 6, 7 and 8, `NOTICE` and the Apache-2.0 text for third party material.
