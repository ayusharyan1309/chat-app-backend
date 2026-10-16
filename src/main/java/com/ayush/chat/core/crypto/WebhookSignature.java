package com.ayush.chat.core.crypto;

/**
 * The one signature scheme for every outbound HTTP callback (tenant webhooks and the webhook push
 * connector):
 * <pre>
 *   X-Chat-Timestamp: &lt;unix seconds&gt;
 *   X-Chat-Signature: sha256=&lt;hex HMAC-SHA256(secret, timestamp + "." + body)&gt;
 * </pre>
 * Binding the timestamp into the MAC lets receivers reject replays (e.g. older than five minutes).
 */
public final class WebhookSignature {

    public static final String SIGNATURE_HEADER = "X-Chat-Signature";
    public static final String TIMESTAMP_HEADER = "X-Chat-Timestamp";
    public static final String EVENT_HEADER = "X-Chat-Event";
    public static final String DELIVERY_HEADER = "X-Chat-Delivery";

    private WebhookSignature() {
    }

    public static String sign(String secret, long timestampSeconds, String body) {
        return "sha256=" + HmacSigner.ofSecret(secret).signHex(timestampSeconds + "." + body);
    }

    public static boolean verify(String secret, long timestampSeconds, String body, String presented) {
        return HmacSigner.matches(sign(secret, timestampSeconds, body), presented);
    }
}
