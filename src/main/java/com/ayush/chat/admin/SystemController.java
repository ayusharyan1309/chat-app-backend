package com.ayush.chat.admin;

import com.ayush.chat.admin.SystemViews.ConnectorRequest;
import com.ayush.chat.admin.SystemViews.ConnectorState;
import com.ayush.chat.admin.SystemViews.FeatureView;
import com.ayush.chat.admin.SystemViews.KindCatalog;
import com.ayush.chat.admin.SystemViews.SystemOverview;
import com.ayush.chat.chat.metrics.MetricsService;
import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.connector.ConnectorRegistry;
import com.ayush.chat.core.connector.HealthStatus;
import com.ayush.chat.core.connector.runtime.PlatformConnectors;
import com.ayush.chat.tenant.Feature;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * Platform-level operations: health overview, metrics, the connector catalogue and the active
 * infrastructure configuration.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class SystemController {

    private final SystemAdminService system;
    private final PlatformConnectors platform;
    private final ConnectorRegistry registry;
    private final MetricsService metrics;

    @GetMapping("/system/overview")
    public SystemOverview overview() {
        return system.overview();
    }

    @GetMapping("/system/metrics")
    public List<MetricsService.MinutePoint> metrics(@RequestParam(defaultValue = "60") int minutes) {
        return metrics.series(minutes);
    }

    @GetMapping("/connectors")
    public List<KindCatalog> connectors() {
        return Arrays.stream(ConnectorKind.values())
                .map(k -> new KindCatalog(k.slug(), k.getDisplayName(), k.getDescription(), k.isTenantOverridable(),
                        registry.descriptors(k)))
                .toList();
    }

    @GetMapping("/features")
    public List<FeatureView> features() {
        return Arrays.stream(Feature.values()).map(FeatureView::of).toList();
    }

    @GetMapping("/infrastructure")
    public List<ConnectorState> infrastructure() {
        return system.states();
    }

    @GetMapping("/infrastructure/{kind}")
    public ConnectorState infrastructure(@PathVariable String kind) {
        return system.state(ConnectorKind.fromSlug(kind));
    }

    @PutMapping("/infrastructure/{kind}")
    public ConnectorState apply(@PathVariable String kind, @RequestBody ConnectorRequest request) {
        return system.apply(ConnectorKind.fromSlug(kind), request.provider(), request.settings());
    }

    @PostMapping("/infrastructure/{kind}/test")
    public HealthStatus test(@PathVariable String kind, @RequestBody ConnectorRequest request) {
        return platform.test(ConnectorKind.fromSlug(kind), request.provider(), request.settings());
    }

    @DeleteMapping("/infrastructure/{kind}")
    public ConnectorState reset(@PathVariable String kind) {
        return system.reset(ConnectorKind.fromSlug(kind));
    }
}
