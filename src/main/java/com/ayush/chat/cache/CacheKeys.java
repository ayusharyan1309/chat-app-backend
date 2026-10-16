package com.ayush.chat.cache;

/**
 * Every key the platform writes to the shared cache, in one place, so the namespace is visible and
 * collisions are impossible. Connectors may add a deployment-wide prefix (e.g. the Redis key prefix).
 */
public final class CacheKeys {

    private CacheKeys() {
    }

    // presence & cluster
    public static String presence(String tenant, String userId) {
        return "presence:" + tenant + ":" + userId;
    }

    public static String clusterSlots() {
        return "cluster:slots";
    }

    public static String clusterNode(long slot) {
        return "cluster:node:" + slot;
    }

    // quotas & rate limits
    public static String dailyMessages(String tenant, String isoDay) {
        return "quota:msg:" + tenant + ":" + isoDay;
    }

    public static String storageBytes(String tenant) {
        return "quota:storage:" + tenant;
    }

    public static String sendRate(String tenant, String userId, long epochSecond) {
        return "rate:" + tenant + ":" + userId + ":" + epochSecond;
    }

    // throughput metrics
    public static String minuteMetric(String metric, long epochMinute) {
        return "metrics:" + metric + ":" + epochMinute;
    }

    public static String dailyMetric(String metric, String isoDay) {
        return "metrics:" + metric + ":day:" + isoDay;
    }

    // coordination & security
    public static String lock(String resource) {
        return "lock:" + resource;
    }

    public static String loginFailures(String email, String ip) {
        return "login-fail:" + email + ":" + ip;
    }

    public static String loginFailures(String email) {
        return "login-fail:" + email;
    }
}
