package com.analytics.platform.query;

import com.analytics.platform.config.AnalyticsProperties;
import com.analytics.platform.query.QueryResults.CountResult;
import com.analytics.platform.query.QueryResults.EventTypeCounts;
import com.analytics.platform.query.QueryResults.Revenue;
import com.analytics.platform.query.QueryResults.TimeSeries;
import com.analytics.platform.query.QueryResults.TopUsers;
import com.analytics.platform.tenant.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/** Validates parameters, then runs each query through the tenant-scoped cache. */
@Service
public class AnalyticsService {

    private final ClickHouseQueryRepository repository;
    private final QueryCache cache;
    private final MeterRegistry registry;
    private final long maxRangeDays;
    private final int maxTopN;

    public AnalyticsService(ClickHouseQueryRepository repository, QueryCache cache,
                            MeterRegistry registry, AnalyticsProperties props) {
        this.repository = repository;
        this.cache = cache;
        this.registry = registry;
        this.maxRangeDays = props.query().maxRangeDays();
        this.maxTopN = props.query().maxTopN();
    }

    public CountResult count(Instant from, Instant to, String eventType) {
        validateRange(from, to);
        validateEventType(eventType);
        String tenant = TenantContext.getTenantId();
        return run("count", tenant, fp(from, to, eventType), CountResult.class,
                () -> repository.count(tenant, from.toEpochMilli(), to.toEpochMilli(), eventType));
    }

    public EventTypeCounts byType(Instant from, Instant to) {
        validateRange(from, to);
        String tenant = TenantContext.getTenantId();
        return run("by-type", tenant, fp(from, to), EventTypeCounts.class,
                () -> repository.byType(tenant, from.toEpochMilli(), to.toEpochMilli()));
    }

    public TimeSeries timeSeries(Instant from, Instant to, String bucketName, String eventType) {
        validateRange(from, to);
        validateEventType(eventType);
        Bucket bucket = Bucket.parse(bucketName);
        String tenant = TenantContext.getTenantId();
        return run("timeseries", tenant, fp(from, to, bucket, eventType), TimeSeries.class,
                () -> repository.timeSeries(tenant, from.toEpochMilli(), to.toEpochMilli(), bucket, eventType));
    }

    public Revenue revenue(Instant from, Instant to) {
        validateRange(from, to);
        String tenant = TenantContext.getTenantId();
        return run("revenue", tenant, fp(from, to), Revenue.class,
                () -> repository.revenue(tenant, from.toEpochMilli(), to.toEpochMilli()));
    }

    public TopUsers topUsers(Instant from, Instant to, int limit) {
        validateRange(from, to);
        if (limit < 1 || limit > maxTopN) {
            throw new IllegalArgumentException("limit must be between 1 and " + maxTopN);
        }
        String tenant = TenantContext.getTenantId();
        return run("top-users", tenant, fp(from, to, limit), TopUsers.class,
                () -> repository.topUsers(tenant, from.toEpochMilli(), to.toEpochMilli(), limit));
    }

    private <T> T run(String name, String tenant, String fingerprint, Class<T> type, Supplier<T> loader) {
        // End-to-end service latency (cache + ClickHouse). ClickHouse-only latency is analytics.clickhouse.query.
        return registry.timer("analytics.query", "query", name)
                .record(() -> cache.getOrLoad(tenant, name, fingerprint, type, loader));
    }

    private void validateRange(Instant from, Instant to) {
        if (from == null || to == null || !from.isBefore(to)) {
            throw new IllegalArgumentException("'from' must be before 'to'");
        }
        if (Duration.between(from, to).compareTo(Duration.ofDays(maxRangeDays)) > 0) {
            throw new IllegalArgumentException("time range must not exceed " + maxRangeDays + " days");
        }
    }

    private static void validateEventType(String eventType) {
        if (eventType != null && (eventType.isBlank() || eventType.length() > 100)) {
            throw new IllegalArgumentException("eventType must be 1-100 characters");
        }
    }

    private static String fp(Object... parts) {
        StringBuilder sb = new StringBuilder();
        for (Object part : parts) {
            sb.append(part instanceof Instant i ? i.toEpochMilli() : part).append('|');
        }
        return sb.toString();
    }
}
