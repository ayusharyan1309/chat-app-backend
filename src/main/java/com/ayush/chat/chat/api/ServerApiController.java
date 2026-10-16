package com.ayush.chat.chat.api;

import com.ayush.chat.admin.AuditService;
import com.ayush.chat.auth.JwtService;
import com.ayush.chat.auth.Principals.TenantServerPrincipal;
import com.ayush.chat.chat.ChatService;
import com.ayush.chat.chat.Views.MessageView;
import com.ayush.chat.chat.Views.UserView;
import com.ayush.chat.core.cluster.ClusterService;
import com.ayush.chat.core.config.TenantConnectors;
import com.ayush.chat.core.error.ApiException;
import com.ayush.chat.store.model.ChatMessage;
import com.ayush.chat.store.model.StoreStats;
import com.ayush.chat.tenant.Feature;
import com.ayush.chat.tenant.TenantDirectory;
import com.ayush.chat.tenant.TenantSnapshot;
import com.ayush.chat.tenant.UsageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Server-to-server API for tenant backends, authenticated with {@code X-API-Key}.
 */
@RestController
@RequestMapping("/api/v1/server")
@RequiredArgsConstructor
public class ServerApiController {

    private final ChatService chat;
    private final JwtService jwt;
    private final TenantDirectory tenants;
    private final TenantConnectors connectors;
    private final UsageService usage;
    private final ClusterService cluster;
    private final AuditService audit;

    public record TokenRequest(String userId, Integer ttlMinutes) {
    }

    public record TokenResponse(String token, Instant expiresAt) {
    }

    public record ServerMessageRequest(String senderId, ChatMessage.Type type, String content, String clientMsgId,
                                       ChatMessage.Attachment attachment, Map<String, Object> metadata) {
    }

    @PostMapping("/users")
    public UserView upsertUser(@AuthenticationPrincipal TenantServerPrincipal server, @RequestBody ChatService.UserInput input) {
        return chat.upsertUser(server.tenant(), input);
    }

    @GetMapping("/users")
    public Map<String, Object> users(@AuthenticationPrincipal TenantServerPrincipal server,
                                     @RequestParam(required = false) String q,
                                     @RequestParam(defaultValue = "0") int offset,
                                     @RequestParam(defaultValue = "50") int limit) {
        return Map.of("items", chat.searchUsers(server.tenant(), null, q, offset, limit, true),
                "total", chat.countUsers(server.tenant()));
    }

    @GetMapping("/users/{id}")
    public UserView user(@AuthenticationPrincipal TenantServerPrincipal server, @PathVariable String id) {
        return chat.getUser(server.tenant(), id);
    }

    @DeleteMapping("/users/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@AuthenticationPrincipal TenantServerPrincipal server, @PathVariable String id) {
        chat.deactivateUser(server.tenant(), id);
        audit.record("user.deactivated", "user", id, server.tenant(), null);
    }

    @PostMapping("/tokens")
    public TokenResponse token(@AuthenticationPrincipal TenantServerPrincipal server, @RequestBody TokenRequest request) {
        if (request.userId() == null || request.userId().isBlank()) {
            throw ApiException.badRequest("userId is required");
        }
        TenantSnapshot t = tenants.requireUsable(server.tenant());
        chat.requireActiveUser(t.slug(), request.userId());
        int ttl = request.ttlMinutes() != null ? request.ttlMinutes() : t.auth().tokenTtlMinutes();
        if (ttl < 1 || ttl > 60 * 24 * 30) {
            throw ApiException.badRequest("ttlMinutes must be between 1 and 43200");
        }
        JwtService.IssuedToken token = jwt.issueUser(t.slug(), request.userId(), Duration.ofMinutes(ttl));
        return new TokenResponse(token.token(), token.expiresAt());
    }

    @PostMapping("/conversations/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public MessageView send(@AuthenticationPrincipal TenantServerPrincipal server, @PathVariable String id,
                            @RequestBody ServerMessageRequest request) {
        if (request.senderId() == null) {
            throw ApiException.badRequest("senderId is required");
        }
        return chat.sendAsServer(server.tenant(), request.senderId(), id, new ChatService.SendInput(request.clientMsgId(),
                request.type(), request.content(), request.attachment(), null, false, request.metadata())).message();
    }

    @GetMapping("/stats")
    public Map<String, Object> stats(@AuthenticationPrincipal TenantServerPrincipal server) {
        TenantSnapshot t = tenants.requireUsable(server.tenant());
        t.require(Feature.ANALYTICS);
        StoreStats stats = connectors.store(t.slug()).stats(t.slug());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("users", stats.users());
        out.put("conversations", stats.conversations());
        out.put("messages", stats.messages());
        out.put("messagesToday", usage.messagesToday(t.slug()));
        out.put("connections", cluster.connections(t.slug()));
        out.put("usage", usage.history(t.slug(), 30));
        return out;
    }
}
