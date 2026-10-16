package com.ayush.chat.admin.controller;

import com.ayush.chat.admin.dto.request.ConnectorRequest;
import com.ayush.chat.admin.dto.response.ConnectorOverrideView;
import com.ayush.chat.admin.service.TenantAdminService;
import com.ayush.chat.core.connector.HealthStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Dedicated per-tenant connectors (message store, push) that override the platform default. */
@RestController
@RequestMapping("/api/admin/tenants/{slug}/connectors/{kind}")
@RequiredArgsConstructor
public class TenantConnectorController {

    private final TenantAdminService admin;

    @GetMapping
    public ConnectorOverrideView get(@PathVariable String slug, @PathVariable String kind) {
        return admin.connector(slug, kind);
    }

    @PutMapping
    public ConnectorOverrideView apply(@PathVariable String slug, @PathVariable String kind, @RequestBody ConnectorRequest request) {
        return admin.applyConnector(slug, kind, request);
    }

    @PostMapping("/test")
    public HealthStatus test(@PathVariable String slug, @PathVariable String kind, @RequestBody ConnectorRequest request) {
        return admin.testConnector(slug, kind, request);
    }

    @DeleteMapping
    public ConnectorOverrideView inherit(@PathVariable String slug, @PathVariable String kind) {
        return admin.resetConnector(slug, kind);
    }
}
