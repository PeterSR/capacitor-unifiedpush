import type { PermissionState, PluginListenerHandle } from '@capacitor/core';

/** Web Push subscription keys, both base64url encoded without padding (RFC 8291). */
export interface PubKeySet {
  /** P-256 public key, uncompressed point. */
  p256dh: string;
  /** Auth secret. */
  auth: string;
}

export interface RegisterOptions {
  /** Registration instance, for apps that need several endpoints. Defaults to "default". */
  instance?: string;
  /** Free text passed to the distributor, shown to the user in some distributors. */
  messageForDistributor?: string;
  /** VAPID public key (base64url, 87 characters) the application server will sign with. */
  vapid?: string;
}

export interface EndpointEvent {
  instance: string;
  /** The push endpoint URL the application server posts to. */
  endpoint: string;
  /**
   * Keys the application server needs to encrypt messages (RFC 8291).
   * Null when the distributor did not provide a key set.
   */
  pubKeySet: PubKeySet | null;
  /** True when the distributor says this endpoint is temporary and a new one will follow. */
  temporary?: boolean;
}

export type RegistrationFailedReason =
  'INTERNAL_ERROR' | 'NETWORK' | 'ACTION_REQUIRED' | 'VAPID_REQUIRED';

export interface RegistrationFailedEvent {
  instance: string;
  reason: RegistrationFailedReason;
}

export interface UnregisteredEvent {
  /** Unique id, pass it to `ackUnregistrations`. Delivered at least once until acknowledged. */
  id: string;
  /** When the plugin learned about it, ms since epoch. */
  receivedAt: number;
  instance: string;
  /** The last endpoint known for this instance, so you can delete it on your server. Null when unknown. */
  endpoint: string | null;
  pubKeySet: PubKeySet | null;
}

export interface PushMessageEvent {
  /** Unique id, pass it to `ackMessages`. The same message can be delivered more than once until acknowledged. */
  id: string;
  /** When the plugin received the message, ms since epoch. */
  receivedAt: number;
  instance: string;
  /** The decrypted payload as a UTF-8 string. Messages that fail decryption are dropped, never delivered. */
  data: string;
  /** `data` parsed as JSON, when it is a JSON object. */
  json?: Record<string, unknown>;
}

/**
 * The optional top level `notification` object of a push message. The plugin never reads this type
 * at runtime, it documents what the native side understands (Android).
 */
export interface PushNotificationSpec {
  /** Identifies the notification. The same id updates it in place. */
  id: string;
  title?: string;
  body?: string;
  ongoing?: boolean;
  actions?: { id: string; title: string }[];
  data?: Record<string, unknown>;
  channel_id?: string;
  silent?: boolean;
  cancel?: boolean;
  /** Milliseconds since epoch, set by the sender, used as replay guard. */
  timestamp?: number;
  /**
   * Name of a drawable resource in the app (for example `ic_stat_timer`) used as the small icon.
   * Must match `^ic_stat_[a-z0-9_]{1,56}$`. Falls back to the plugin's default icon when invalid, not
   * found or not loadable. Small icons must be monochrome (white on transparent).
   */
  icon?: string;
  /** Accent colour as `#RRGGBB`. Ignored when it does not match exactly. */
  color?: string;
}

export interface NotificationActionEvent {
  /** Unique id, pass it to `ackActions`. Delivered at least once until acknowledged. */
  id: string;
  /** When the tap was accepted, ms since epoch. */
  receivedAt: number;
  /** The `id` of the notification, as sent by the server. */
  notificationId: string;
  /** The `id` of the tapped action button. */
  actionId: string;
  /** The `data` object of the notification, `{}` when none was sent. */
  data: Record<string, unknown>;
}

export interface NotificationPermissionStatus {
  /** Below Android 13 this is 'granted' unless the user switched notifications off for the app. */
  notifications: PermissionState;
}

/**
 * Error codes in rejections from `register`, `saveDistributor` and the `ack*` methods:
 * - `NO_DISTRIBUTOR`: no UnifiedPush distributor app is installed
 * - `NO_DISTRIBUTOR_SELECTED`: several are installed and none was chosen
 * - `NO_ACTIVITY`: the app has no foreground activity to start the registration from
 * - `INVALID_ARGUMENT`: a required argument is missing or empty
 * - `UNKNOWN_DISTRIBUTOR`: `saveDistributor` was given a package that is not an installed distributor
 * - `INVALID_VAPID`: the `vapid` key has the wrong format
 * - `REGISTER_FAILED`: any other failure
 */
export interface UnifiedPushPlugin {
  /** Package names of installed UnifiedPush distributors. */
  getDistributors(): Promise<{ distributors: string[] }>;

  /** The distributor package saved earlier, or null. */
  getSavedDistributor(): Promise<{ distributor: string | null }>;

  /** Saves the distributor to register with. Call before `register`. */
  saveDistributor(options: { distributor: string }): Promise<void>;

  /**
   * Registers with the saved distributor. When none is saved, uses the user's default
   * distributor (the OS may show a picker) and saves it. Resolves once the request has been
   * sent; the endpoint arrives later as an `endpoint` event.
   * Rejects with code `NO_DISTRIBUTOR` when no distributor is installed.
   */
  register(options?: RegisterOptions): Promise<{ distributor: string }>;

  /**
   * Unregisters an instance and forgets its stored endpoint. Resolves with the endpoint that was
   * dropped (null if none), so you can delete it on your server.
   */
  unregister(options?: { instance?: string }): Promise<{ endpoint: string | null }>;

  /**
   * The most recent endpoint for an instance, stored by the plugin. Use it after an app
   * restart to resync your server. `endpoint` is null when there is none.
   */
  getLastEndpoint(options?: { instance?: string }): Promise<{
    instance: string;
    endpoint: string | null;
    pubKeySet: PubKeySet | null;
    temporary?: boolean;
  }>;

  /**
   * Messages not yet acknowledged, oldest first (at most 50, at most 7 days old).
   * Every decrypted message is stored until you call `ackMessages`.
   */
  getPendingMessages(): Promise<{ messages: PushMessageEvent[] }>;
  ackMessages(options: { ids: string[] }): Promise<void>;

  /**
   * `unregistered` events not yet acknowledged, so an app that was not running when the
   * distributor dropped a registration still learns which endpoint to delete (at most 50, 7 days).
   */
  getPendingUnregistrations(): Promise<{ unregistrations: UnregisteredEvent[] }>;
  ackUnregistrations(options: { ids: string[] }): Promise<void>;

  /** Accepted notification action taps not yet acknowledged (at most 50, at most 7 days old). */
  getPendingActions(): Promise<{ actions: NotificationActionEvent[] }>;
  ackActions(options: { ids: string[] }): Promise<void>;

  checkPermissions(): Promise<NotificationPermissionStatus>;
  requestPermissions(): Promise<NotificationPermissionStatus>;

  addListener(
    eventName: 'endpoint',
    listener: (event: EndpointEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'registrationFailed',
    listener: (event: RegistrationFailedEvent) => void,
  ): Promise<PluginListenerHandle>;
  /** Stored and redelivered like 'message', see `ackUnregistrations`. */
  addListener(
    eventName: 'unregistered',
    listener: (event: UnregisteredEvent) => void,
  ): Promise<PluginListenerHandle>;
  /**
   * Delivery is at least once. Each decrypted message is stored with an `id` until you
   * `ackMessages`. It is emitted live when a listener is attached, and every unacknowledged
   * message is emitted again, oldest first, each time a 'message' listener attaches (this covers
   * a cold start and a WebView reload). Deduplicate by `id` and acknowledge after handling.
   */
  addListener(
    eventName: 'message',
    listener: (event: PushMessageEvent) => void,
  ): Promise<PluginListenerHandle>;
  /**
   * An accepted action tap is stored with an `id` until you `ackActions`, emitted live when a
   * listener is attached, and emitted again on every listener attach until acknowledged. Same
   * at-least-once rule as 'message'. Taps without a valid one-shot token are ignored.
   */
  addListener(
    eventName: 'notificationAction',
    listener: (event: NotificationActionEvent) => void,
  ): Promise<PluginListenerHandle>;

  removeAllListeners(): Promise<void>;
}
