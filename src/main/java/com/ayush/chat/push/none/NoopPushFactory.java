package com.ayush.chat.push.none;

import com.ayush.chat.core.connector.ConnectorDescriptor;
import com.ayush.chat.core.connector.ConnectorFactory;
import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.connector.ConnectorSettings;
import com.ayush.chat.core.connector.HealthStatus;
import com.ayush.chat.core.connector.Maturity;
import com.ayush.chat.push.PushSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class NoopPushFactory implements ConnectorFactory<PushSender> {

    private static final ConnectorDescriptor DESCRIPTOR = new ConnectorDescriptor(ConnectorKind.PUSH,
            "none", "Disabled",
            "Push notifications are not sent (logged at debug level).", "None", Maturity.GA, List.of(), List.of());

    @Override
    public ConnectorDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public PushSender create(ConnectorSettings settings) {
        return new NoopPushSender();
    }

    @Slf4j
    static class NoopPushSender implements PushSender {
        @Override
        public String providerId() {
            return "none";
        }

        @Override
        public HealthStatus health() {
            return HealthStatus.up("Push disabled");
        }

        @Override
        public PushResult send(PushNotification notification) {
            log.debug("Push (disabled) to {}/{}: {}", notification.tenantId(), notification.userId(), notification.title());
            return PushResult.none();
        }

        @Override
        public void close() {
            // nothing to release
        }
    }
}
