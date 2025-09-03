package com.ayush.chat.security;

import com.ayush.chat.model.User;
import com.ayush.chat.service.UserService;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.security.Principal;

@Component
public class FirebaseChannelInterceptor implements ChannelInterceptor {

    @Autowired
    private final FirebaseAuth firebaseAuth;

    @Autowired
    private final UserService appUserService;

    @Autowired
    public FirebaseChannelInterceptor(FirebaseAuth firebaseAuth, UserService appUserService) {
        this.firebaseAuth = firebaseAuth;
        this.appUserService = appUserService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            String token = accessor.getFirstNativeHeader("Firebase-Token");
            if (token != null) {
                try {
                    FirebaseToken decodedToken = firebaseAuth.verifyIdToken(token);
                    String uid = decodedToken.getUid();
                    User user = appUserService.getUserByUid(uid);

                    String email = user.getEmail();

                    // Set principal name to email for correct WebSocket routing
                    Principal principal = new ChatPrincipal(email, "APP" , uid, 1L);
                    accessor.setUser(principal);
                } catch (FirebaseAuthException e) {
                    e.printStackTrace(); // Handle or log error appropriately
                }
            }
        }
        return message;
    }
}
