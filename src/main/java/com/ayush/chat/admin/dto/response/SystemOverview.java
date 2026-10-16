package com.ayush.chat.admin.dto.response;

import com.ayush.chat.chat.dto.response.MinutePoint;
import com.ayush.chat.core.cluster.ClusterService;

import java.time.Instant;
import java.util.List;

public record SystemOverview(String version, String nodeId, Instant startedAt, long uptimeSeconds,
                             List<ComponentView> components, List<ClusterService.NodeInfo> nodes, PlatformTotals totals,
                             List<MinutePoint> throughput) {
}
