package com.ayush.chat.chat.websocket;

import com.ayush.chat.core.config.PlatformProperties;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

/**
 * STOMP over WebSocket. Authentication happens on the STOMP CONNECT frame (token in the
 * {@code Authorization} header), so origins can be open: no cookies are involved.
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthInterceptor authInterceptor;
    private final SessionRegistry sessionRegistry;
    private final PlatformProperties properties;

    /** Private to the broker so it never competes with application @Scheduled jobs. */
    private final ThreadPoolTaskScheduler heartbeatScheduler = heartbeatScheduler();

    private static ThreadPoolTaskScheduler heartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        scheduler.setDaemon(true);
        scheduler.initialize();
        return scheduler;
    }

    @PreDestroy
    void shutdown() {
        heartbeatScheduler.shutdown();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        long heartbeat = properties.getRealtime().getHeartbeat().toMillis();
        registry.enableSimpleBroker("/queue")
                .setHeartbeatValue(new long[]{heartbeat, heartbeat})
                .setTaskScheduler(heartbeatScheduler);
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
        // Events to one session leave in publish order (message.new before its receipts, etc.).
        registry.setPreservePublishOrder(true);
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*");
        registry.addEndpoint("/ws-sockjs").setAllowedOriginPatterns("*").withSockJS();
        // Frames from one session are handled sequentially so a sender's messages keep their order
        // (and get increasing seqs); different sessions still run in parallel.
        registry.setPreserveReceiveOrder(true);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        int threads = properties.getRealtime().getInboundThreads();
        registration.interceptors(authInterceptor);
        registration.taskExecutor().corePoolSize(threads).maxPoolSize(threads);
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        int threads = properties.getRealtime().getOutboundThreads();
        registration.taskExecutor().corePoolSize(threads).maxPoolSize(threads);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        PlatformProperties.Realtime realtime = properties.getRealtime();
        registration.setMessageSizeLimit((int) realtime.getMaxFrameSize().toBytes())
                .setSendBufferSizeLimit((int) realtime.getSendBufferSize().toBytes())
                .setSendTimeLimit((int) realtime.getSendTimeLimit().toMillis())
                .addDecoratorFactory(handler -> new WebSocketHandlerDecorator(handler) {
                    @Override
                    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                        sessionRegistry.register(session);
                        super.afterConnectionEstablished(session);
                    }

                    @Override
                    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                        sessionRegistry.unregister(session);
                        super.afterConnectionClosed(session, status);
                    }
                });
    }
}
