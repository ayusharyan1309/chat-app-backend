package com.ayush.chat.chat.controller;

import com.ayush.chat.auth.dto.IssuedToken;
import com.ayush.chat.auth.security.Principals.TenantServerPrincipal;
import com.ayush.chat.auth.service.UserTokenService;
import com.ayush.chat.chat.dto.command.SendMessageCommand;
import com.ayush.chat.chat.dto.request.IssueUserTokenRequest;
import com.ayush.chat.chat.dto.request.ServerMessageRequest;
import com.ayush.chat.chat.dto.request.UpsertUserRequest;
import com.ayush.chat.chat.dto.response.MessageView;
import com.ayush.chat.chat.dto.response.ServerTokenResponse;
import com.ayush.chat.chat.dto.response.TenantStatsResponse;
import com.ayush.chat.chat.dto.response.UserView;
import com.ayush.chat.chat.service.MessageService;
import com.ayush.chat.chat.service.UserDirectoryService;
import com.ayush.chat.core.cluster.ClusterService;
import com.ayush.chat.core.connector.runtime.TenantConnectors;
import com.ayush.chat.core.dto.Page;
import com.ayush.chat.core.exception.DomainException;
import com.ayush.chat.store.model.StoreStats;
import com.ayush.chat.tenant.model.Feature;
import com.ayush.chat.tenant.model.TenantSnapshot;
import com.ayush.chat.tenant.service.QuotaService;
import com.ayush.chat.tenant.service.TenantDirectory;
import com.ayush.chat.tenant.service.UsageMeter;
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

    @PostMapping("/users")
    public UserView upsertUser(@AuthenticationPrincipal TenantServerPrincipal server, @RequestBody UpsertUserRequest input) {
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
    public ServerTokenResponse token(@AuthenticationPrincipal TenantServerPrincipal server, @RequestBody IssueUserTokenRequest request) {
        if (request.userId() == null || request.userId().isBlank()) {
            throw DomainException.badRequest("userId is required");
        }
        TenantSnapshot t = tenants.requireUsable(server.tenant());
        int ttl = request.ttlMinutes() != null ? request.ttlMinutes() : t.auth().tokenTtlMinutes();
        IssuedToken token = tokens.issue(t.slug(), request.userId(), Duration.ofMinutes(ttl));
        return new ServerTokenResponse(token.token(), token.expiresAt());
    }

    @PostMapping("/conversations/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public MessageView send(@AuthenticationPrincipal TenantServerPrincipal server, @PathVariable String id,
                            @RequestBody ServerMessageRequest request) {
        if (request.senderId() == null) {
            throw DomainException.badRequest("senderId is required");
        }
        return messages.sendAsServer(server.tenant(), request.senderId(), id, new SendMessageCommand(
                request.clientMsgId(), request.type(), request.content(), request.attachment(), null, false,
                request.metadata())).message();
    }

    @GetMapping("/stats")
    public TenantStatsResponse stats(@AuthenticationPrincipal TenantServerPrincipal server) {
        TenantSnapshot t = tenants.requireUsable(server.tenant());
        t.require(Feature.ANALYTICS);
        StoreStats stats = connectors.store(t.slug()).stats(t.slug());
        return new TenantStatsResponse(stats.users(), stats.conversations(), stats.messages(), quotas.messagesToday(t.slug()),
                cluster.connections(t.slug()), usage.history(t.slug(), USAGE_HISTORY_DAYS));
    }
}
