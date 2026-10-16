package com.ayush.chat.store.sql;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Accepts either JDBC URLs or the URI form most hosted databases hand out
 * ({@code postgresql://user:pass@host:5432/db?sslmode=require}, {@code mysql://...}).
 */
public final class JdbcUrls {

    private JdbcUrls() {
    }

    public record Normalized(String jdbcUrl, String username, String password) {
    }

    public static Normalized normalize(String url, String username, String password) {
        String trimmed = url.trim();
        if (trimmed.startsWith("jdbc:")) {
            return new Normalized(trimmed, username, password);
        }
        String scheme;
        if (trimmed.startsWith("postgres://") || trimmed.startsWith("postgresql://")) {
            scheme = "postgresql";
        } else if (trimmed.startsWith("mysql://")) {
            scheme = "mysql";
        } else if (trimmed.startsWith("mariadb://")) {
            scheme = "mariadb";
        } else {
            throw new IllegalArgumentException("Unrecognised database URL: expected jdbc:..., postgresql://... or mysql://...");
        }
        URI uri = URI.create(trimmed);
        String user = username;
        String pass = password;
        if (uri.getRawUserInfo() != null) {
            String[] parts = uri.getRawUserInfo().split(":", 2);
            if (user == null || user.isBlank()) {
                user = decode(parts[0]);
            }
            if ((pass == null || pass.isBlank()) && parts.length > 1) {
                pass = decode(parts[1]);
            }
        }
        int port = uri.getPort();
        String hostPort = uri.getHost() + (port > 0 ? ":" + port : "");
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        return new Normalized("jdbc:" + scheme + "://" + hostPort + path + query, user, pass);
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
