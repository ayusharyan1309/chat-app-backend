package com.ayush.chat.config;

import com.ayush.chat.security.CustomHandshakeHandler;
import com.ayush.chat.security.FirebaseChannelInterceptor;
import com.ayush.chat.security.FirebaseHandshakeInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Autowired
    private FirebaseHandshakeInterceptor firebaseHandshakeInterceptor;
    @Autowired
    private CustomHandshakeHandler customHandshakeHandler;
    @Autowired
    private FirebaseChannelInterceptor firebaseChannelInterceptor;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/queue", "/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
            .addInterceptors(firebaseHandshakeInterceptor)
            .setHandshakeHandler(customHandshakeHandler)
                .setAllowedOrigins("*")
//            .setAllowedOriginPatterns("*")
            .withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(firebaseChannelInterceptor);
    }

    // Remove explicit SimpUserRegistry bean definition. Spring will auto-configure this for you.
}
