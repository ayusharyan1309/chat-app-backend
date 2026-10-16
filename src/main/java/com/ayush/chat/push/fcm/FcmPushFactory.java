package com.ayush.chat.push.fcm;

import com.ayush.chat.core.connector.ConfigField;
import com.ayush.chat.core.connector.ConnectorDescriptor;
import com.ayush.chat.core.connector.ConnectorFactory;
import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.connector.ConnectorSettings;
import com.ayush.chat.push.PushSender;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class FcmPushFactory implements ConnectorFactory<PushSender> {

    @Override
    public ConnectorDescriptor descriptor() {
        return new ConnectorDescriptor(ConnectorKind.PUSH, "fcm", "Firebase Cloud Messaging",
                "Push to Android, iOS (through FCM's APNs integration) and web clients. Dead tokens are reported for cleanup.",
                "Mobile push", "GA", List.of(),
                List.of(ConfigField.of("credentialsJson", "Service account JSON").secret().required()
                                .placeholder("{\"type\": \"service_account\", \"project_id\": ...}")
                                .description("Firebase console → Project settings → Service accounts → Generate new private key").build(),
                        ConfigField.of("projectId", "Project id")
                                .description("Optional; defaults to project_id from the service account JSON").build()));
    }

    @Override
    public PushSender create(ConnectorSettings settings) {
        return new FcmPushSender(settings.required("credentialsJson"), settings.string("projectId", null));
    }
}
