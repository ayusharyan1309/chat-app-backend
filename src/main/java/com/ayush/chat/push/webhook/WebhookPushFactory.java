package com.ayush.chat.push.webhook;

import com.ayush.chat.core.connector.ConfigField;
import com.ayush.chat.core.connector.ConnectorDescriptor;
import com.ayush.chat.core.connector.ConnectorFactory;
import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.connector.ConnectorSettings;
import com.ayush.chat.push.PushSender;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
public class WebhookPushFactory implements ConnectorFactory<PushSender> {

    @Override
    public ConnectorDescriptor descriptor() {
        return new ConnectorDescriptor(ConnectorKind.PUSH, "webhook", "Webhook",
                "POST each notification as signed JSON to your own push service (APNs, OneSignal, ...).",
                "HTTP", "GA", List.of(),
                List.of(ConfigField.of("url", "Endpoint URL").url().required()
                                .placeholder("https://push.example.com/chat")
                                .description("Receives POST application/json; any 2xx counts as delivered").build(),
                        ConfigField.of("secret", "Signing secret").secret()
                                .description("Requests carry X-Chat-Signature: sha256=<hex HMAC-SHA256 of the body>").build(),
                        ConfigField.of("timeoutMs", "Timeout (ms)").integer(5000)
                                .description("Connect and response timeout per request").build()));
    }

    @Override
    public PushSender create(ConnectorSettings settings) {
        return new WebhookPushSender(settings.required("url"), settings.string("secret", null),
                Duration.ofMillis(settings.integer("timeoutMs", 5000)));
    }
}
