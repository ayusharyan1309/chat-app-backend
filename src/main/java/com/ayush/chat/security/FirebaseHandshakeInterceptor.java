package com.ayush.chat.security;

import com.ayush.chat.model.User;
import com.ayush.chat.service.UserService;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.security.Principal;
import java.util.Map;

@Component
public class FirebaseHandshakeInterceptor implements HandshakeInterceptor {

    @Autowired
    private final FirebaseAuth firebaseAuth;

    @Autowired
    private final UserService appUserService;

    public FirebaseHandshakeInterceptor(FirebaseAuth firebaseAuth, UserService appUserService) {
        this.firebaseAuth = firebaseAuth;
        this.appUserService = appUserService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) throws Exception {

        String firebaseToken = request.getHeaders().getFirst("Firebase-Token");
        if (firebaseToken != null) {
            try {
                FirebaseToken decoded = firebaseAuth.verifyIdToken(firebaseToken);
                String uid = decoded.getUid();
                User user = appUserService.getUserByUid(uid);

                String email = user.getEmail();

                // Set principal name to email for correct WebSocket routing
                Principal principal = new ChatPrincipal(email, "APP" , uid, 1L);
                attributes.put("principal", principal);
                // --- FIX: Also set principal in request for Spring WebSocket routing ---
                request.getHeaders().add("user", email);

            } catch (FirebaseAuthException e) {
                e.printStackTrace();
            }
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {}
}
