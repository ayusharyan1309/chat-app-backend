package com.ayush.chat.chat.api;

import com.ayush.chat.auth.JwtService;
import com.ayush.chat.auth.Principals.TenantServerPrincipal;
import com.ayush.chat.auth.UserTokenService;
import com.ayush.chat.chat.Views.MessageView;
import com.ayush.chat.chat.Views.UserView;
import com.ayush.chat.chat.application.MessageService;
import com.ayush.chat.chat.application.UserDirectoryService;
import com.ayush.chat.chat.application.UserDirectoryService.UserInput;
import com.ayush.chat.core.api.Page;
import com.ayush.chat.core.cluster.ClusterService;
import com.ayush.chat.core.connector.runtime.TenantConnectors;
import com.ayush.chat.core.error.DomainException;
import com.ayush.chat.store.model.ChatMessage;
import com.ayush.chat.store.model.StoreStats;
import com.ayush.chat.tenant.Feature;
import com.ayush.chat.tenant.QuotaService;
import com.ayush.chat.tenant.TenantDirectory;
import com.ayush.chat.tenant.TenantSnapshot;
import com.ayush.chat.tenant.UsageMeter;
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
import java.util.List;
import java.util.Map;

/**
 * Server-to-server API for tenant backends, authenticated with {@code X-API-Key}.
 */
@RestController
@RequestMapping("/api/v1/server")
@RequiredArgsConstructor
public class ServerApiController {

    private static final int USAGE_HISTORY_DAYS = 30;

    private final UserDirectoryService users;
    private final MessageService messages;
    private final UserTokenService tokens;
    private final TenantDirectory tenants;
    private final TenantConnectors connectors;
    private final QuotaService quotas;
    private final UsageMeter usage;
    private final ClusterService cluster;

    public record TokenRequest(String userId, Integer ttlMinutes) {
    }

    public record TokenResponse(String token, Instant expiresAt) {
    }

    public record ServerMessageRequest(String senderId, ChatMessage.Type type, String content, String clientMsgId,
                                       ChatMessage.Attachment attachment, Map<String, Object> metadata) {
    }

    public record TenantStats(long users, long conversations, long messages, long messagesToday, int connections,
                              List<UsageMeter.DailyUsage> usage) {
    }

    @PostMapping("/users")
    public UserView upsertUser(@AuthenticationPrincipal TenantServerPrincipal server, @RequestBody UserInput input) {
        return users.upsert(server.tenant(), input);
    }

    @GetMapping("/users")
    public Page<UserView> users(@AuthenticationPrincipal TenantServerPrincipal server,
                                @RequestParam(required = false) String q,
                                @RequestParam(defaultValue = "0") int offset,
                                @RequestParam(defaultValue = "50") int limit) {
        return new Page<>(users.list(server.tenant(), q, offset, limit), users.count(server.tenant()));
    }

    @GetMapping("/users/{id}")
    public UserView user(@AuthenticationPrincipal TenantServerPrincipal server, @PathVariable String id) {
        return users.get(server.tenant(), id);
    }

    @DeleteMapping("/users/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@AuthenticationPrincipal TenantServerPrincipal server, @PathVariable String id) {
        users.deactivate(server.tenant(), id);
    }

    @PostMapping("/tokens")
    public TokenResponse token(@AuthenticationPrincipal TenantServerPrincipal server, @RequestBody TokenRequest request) {
        if (request.userId() == null || request.userId().isBlank()) {
            throw DomainException.badRequest("userId is required");
        }
        TenantSnapshot t = tenants.requireUsable(server.tenant());
        int ttl = request.ttlMinutes() != null ? request.ttlMinutes() : t.auth().tokenTtlMinutes();
        JwtService.IssuedToken token = tokens.issue(t.slug(), request.userId(), Duration.ofMinutes(ttl));
        return new TokenResponse(token.token(), token.expiresAt());
    }

    @PostMapping("/conversations/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public MessageView send(@AuthenticationPrincipal TenantServerPrincipal server, @PathVariable String id,
                            @RequestBody ServerMessageRequest request) {
        if (request.senderId() == null) {
            throw DomainException.badRequest("senderId is required");
        }
        return messages.sendAsServer(server.tenant(), request.senderId(), id, new MessageService.SendInput(
                request.clientMsgId(), request.type(), request.content(), request.attachment(), null, false,
                request.metadata())).message();
    }

    @GetMapping("/stats")
    public TenantStats stats(@AuthenticationPrincipal TenantServerPrincipal server) {
        TenantSnapshot t = tenants.requireUsable(server.tenant());
        t.require(Feature.ANALYTICS);
        StoreStats stats = connectors.store(t.slug()).stats(t.slug());
        return new TenantStats(stats.users(), stats.conversations(), stats.messages(), quotas.messagesToday(t.slug()),
                cluster.connections(t.slug()), usage.history(t.slug(), USAGE_HISTORY_DAYS));
    }
}
