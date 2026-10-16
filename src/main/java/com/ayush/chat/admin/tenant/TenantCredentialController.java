package com.ayush.chat.admin.tenant;

import com.ayush.chat.admin.tenant.TenantViews.ApiKeyView;
import com.ayush.chat.admin.tenant.TenantViews.NamedRequest;
import com.ayush.chat.tenant.TenantConfig;
import com.ayush.chat.tenant.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** How a tenant authenticates: end-user auth configuration and server API keys. */
@RestController
@RequestMapping("/api/admin/tenants/{slug}")
@RequiredArgsConstructor
public class TenantCredentialController {

    private final TenantAdminService admin;
    private final TenantService tenants;

    @GetMapping("/auth")
    public TenantConfig.Auth auth(@PathVariable String slug) {
        return tenants.getAuth(slug);
    }

    @PutMapping("/auth")
    public TenantConfig.Auth updateAuth(@PathVariable String slug, @RequestBody TenantConfig.Auth request) {
        return admin.setAuth(slug, request);
    }

    @GetMapping("/api-keys")
    public List<ApiKeyView> apiKeys(@PathVariable String slug) {
        return admin.apiKeys(slug);
    }

    @PostMapping("/api-keys")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiKeyView createApiKey(@PathVariable String slug, @RequestBody NamedRequest request) {
        return admin.createApiKey(slug, request.name());
    }

    @DeleteMapping("/api-keys/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeApiKey(@PathVariable String slug, @PathVariable Long id) {
        admin.revokeApiKey(slug, id);
    }
}
