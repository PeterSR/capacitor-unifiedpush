package dev.capacitorunifiedpush;

import java.security.MessageDigest;
import java.security.SecureRandom;

/** Pure security checks, kept free of Android classes so they can be unit tested. */
final class Guards {

    /** Timestamps further than this in the future are rejected (clock skew allowance). */
    static final long MAX_FUTURE_MS = 24L * 60 * 60 * 1000;

    private Guards() {}

    /** A fresh random one-shot token, 128 bits as hex. */
    static String newNonce(SecureRandom random) {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** Constant time comparison. A missing stored or presented nonce never matches. */
    static boolean nonceMatches(String stored, String presented) {
        if (stored == null || presented == null) {
            return false;
        }
        return MessageDigest.isEqual(stored.getBytes(java.nio.charset.StandardCharsets.UTF_8), presented.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Whether a notification operation with {@code incoming} timestamp may be applied.
     *
     * @param stored the last applied timestamp for this notification id, or null
     * @param incoming the timestamp in the message, or null when the sender did not set one
     * @param now current time in ms since epoch
     */
    static boolean acceptTimestamp(Long stored, Long incoming, long now) {
        if (incoming == null) {
            return true;
        }
        if (incoming > now + MAX_FUTURE_MS) {
            return false;
        }
        return stored == null || incoming > stored;
    }
}
