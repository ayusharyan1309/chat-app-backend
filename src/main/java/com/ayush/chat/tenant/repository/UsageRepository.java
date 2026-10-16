package com.ayush.chat.tenant.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.Map;

/** {@code platform_usage_daily}: one row per tenant and UTC day, incremented in batches. */
@Repository
@RequiredArgsConstructor
public class UsageRepository {

    private static final String INCREMENT = "UPDATE platform_usage_daily SET messages = messages + ?, media_bytes = media_bytes + ?,"
            + " api_calls = api_calls + ? WHERE tenant_slug = ? AND usage_day = ?";
    private static final String INSERT = "INSERT INTO platform_usage_daily (tenant_slug, usage_day, messages, media_bytes, api_calls)"
            + " VALUES (?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbc;

    /** Portable upsert: update, else insert, else (lost the insert race) update again. */
    public void add(String tenant, LocalDate day, long messages, long mediaBytes, long apiCalls) {
        Date sqlDay = Date.valueOf(day);
        if (jdbc.update(INCREMENT, messages, mediaBytes, apiCalls, tenant, sqlDay) > 0) {
            return;
        }
        try {
            jdbc.update(INSERT, tenant, sqlDay, messages, mediaBytes, apiCalls);
        } catch (DuplicateKeyException race) {
            jdbc.update(INCREMENT, messages, mediaBytes, apiCalls, tenant, sqlDay);
        }
    }

    /** Adds stored totals since {@code from} into {@code rows} (keyed by ISO day, values: messages, media, api). */
    public void accumulate(String tenant, LocalDate from, Map<String, long[]> rows) {
        jdbc.query("SELECT usage_day, messages, media_bytes, api_calls FROM platform_usage_daily"
                + " WHERE tenant_slug = ? AND usage_day >= ?", rs -> {
            long[] v = rows.get(rs.getDate(1).toLocalDate().toString());
            if (v != null) {
                v[0] += rs.getLong(2);
                v[1] += rs.getLong(3);
                v[2] += rs.getLong(4);
            }
        }, tenant, Date.valueOf(from));
    }

    public long totalMediaBytes(String tenant) {
        Long total = jdbc.queryForObject("SELECT COALESCE(SUM(media_bytes), 0) FROM platform_usage_daily WHERE tenant_slug = ?",
                Long.class, tenant);
        return total == null ? 0 : total;
    }
}
