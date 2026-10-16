package com.ayush.chat.chat.event;

import com.ayush.chat.core.util.Json;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ControlMessageTest {

    @Test
    void wireFormatUsesStableIds() {
        String json = Json.write(ControlMessage.blockChanged("acme", "u1", "u2"));
        assertThat(json).contains("\"type\":\"block.changed\"").contains("\"ref\":\"u1|u2\"");
        assertThat(Json.read(json, ControlMessage.class)).isEqualTo(ControlMessage.blockChanged("acme", "u1", "u2"));
    }

    @Test
    void unknownTypesFromNewerNodesAreTolerated() {
        ControlMessage message = Json.read("{\"type\":\"something.new\",\"tenant\":\"acme\"}", ControlMessage.class);
        assertThat(message.type()).isEqualTo(ControlType.UNKNOWN);
        assertThat(message.tenant()).isEqualTo("acme");
    }
}
