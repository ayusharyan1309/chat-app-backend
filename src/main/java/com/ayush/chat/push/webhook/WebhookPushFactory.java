package com.ayush.chat.push.webhook;

import com.ayush.chat.core.connector.ConfigField;
import com.ayush.chat.core.connector.ConnectorDescriptor;
import com.ayush.chat.core.connector.ConnectorFactory;
import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.connector.ConnectorSettings;
import com.ayush.chat.core.connector.Maturity;
import com.ayush.chat.core.crypto.WebhookSignature;
import com.ayush.chat.core.net.OutboundUrlPolicy;
import com.ayush.chat.push.PushSender;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * GA: {@code WebhookPushSenderTest} posts to a real HTTP receiver in CI (signing, status handling,
 * receiver reports, timeouts, redirects); the receiving service is the tenant's own.
 */
@Component
public class WebhookPushFactory implements ConnectorFactory<PushSender> {

    private static final int DEFAULT_TIMEOUT_MS = 5000;

    private static final ConnectorDescriptor DESCRIPTOR = new ConnectorDescriptor(ConnectorKind.PUSH,
            "webhook", "Webhook",
            "POST each notification as signed JSON to your own push service (APNs, OneSignal, ...).",
            "HTTP", Maturity.GA, List.of(),
            List.of(ConfigField.of("url", "Endpoint URL").url().required()
                            .placeholder("https://push.example.com/chat")
                            .description("Receives POST application/json; any 2xx counts as delivered").build(),
                    ConfigField.of("secret", "Signing secret").secret()
                            .description("Signed like tenant webhooks: " + WebhookSignature.TIMESTAMP_HEADER
                                    + ": <unix seconds> and " + WebhookSignature.SIGNATURE_HEADER
                                    + ": sha256=<hex HMAC-SHA256 of timestamp + \".\" + body>").build(),
                    ConfigField.of("timeoutMs", "Timeout (ms)").integer(DEFAULT_TIMEOUT_MS)
                            .description("Connect and response timeout per request").build()));

    private final OutboundUrlPolicy urlPolicy;

    public WebhookPushFactory(OutboundUrlPolicy urlPolicy) {
        this.urlPolicy = urlPolicy;
    }

    @Override
    public ConnectorDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public PushSender create(ConnectorSettings settings) {
        String url = settings.required("url");
        urlPolicy.check(url);
        return new WebhookPushSender(url, settings.string("secret", null),
                Duration.ofMillis(settings.integer("timeoutMs", DEFAULT_TIMEOUT_MS)), uri -> urlPolicy.check(uri.toString()));
    }
}
