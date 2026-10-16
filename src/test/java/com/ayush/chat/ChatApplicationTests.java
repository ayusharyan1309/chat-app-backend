package com.ayush.chat;

import com.ayush.chat.core.config.InfrastructureService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:control-context;DB_CLOSE_DELAY=-1",
        "platform.connectors.message-store.provider=memory",
        "platform.connectors.blob-store.settings.directory=target/test-media"
})
class ChatApplicationTests {

    @Autowired
    InfrastructureService infrastructure;

    @Test
    void contextLoadsWithZeroExternalDependencies() {
        assertThat(infrastructure.messageStore().health().healthy()).isTrue();
        assertThat(infrastructure.cache().health().healthy()).isTrue();
        assertThat(infrastructure.bus().health().healthy()).isTrue();
    }
}
