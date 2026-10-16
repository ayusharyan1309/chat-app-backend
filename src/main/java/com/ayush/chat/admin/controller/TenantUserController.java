package com.ayush.chat.admin.controller;

import com.ayush.chat.admin.dto.response.ImpersonationTokenResponse;
import com.ayush.chat.admin.service.TenantAdminService;
import com.ayush.chat.chat.dto.request.UpsertUserRequest;
import com.ayush.chat.chat.dto.response.UserView;
import com.ayush.chat.chat.service.UserDirectoryService;
import com.ayush.chat.core.dto.Page;
import com.ayush.chat.tenant.service.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** A tenant's end users, as seen by operators. */
@RestController
@RequestMapping("/api/admin/tenants/{slug}/users")
@RequiredArgsConstructor
public class TenantUserController {

    private final TenantAdminService admin;
    private final TenantService tenants;
    private final UserDirectoryService users;

    @GetMapping
    public Page<UserView> list(@PathVariable String slug, @RequestParam(required = false) String q,
                               @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "50") int limit) {
        tenants.get(slug);
        return new Page<>(users.list(slug, q, offset, limit), users.count(slug));
    }

    @PostMapping
    public UserView upsert(@PathVariable String slug, @RequestBody UpsertUserRequest input) {
        return admin.upsertUser(slug, input);
    }

    /** Impersonation token for testing (e.g. the dashboard playground). Always audited. */
    @PostMapping("/{userId}/token")
    public ImpersonationTokenResponse token(@PathVariable String slug, @PathVariable String userId) {
        return admin.impersonate(slug, userId);
    }
}
