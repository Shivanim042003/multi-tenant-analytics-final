package com.analytics.platform.query;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.analytics.platform.support.TestProps;
import com.analytics.platform.tenant.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AnalyticsServiceTest {

    private final AnalyticsService service = new AnalyticsService(
            mock(ClickHouseQueryRepository.class), mock(QueryCache.class),
            new SimpleMeterRegistry(), TestProps.defaults());

    @Test
    void rejectsRangeEvenOneMillisecondOverLimit() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = from.plus(Duration.ofDays(92)).plusMillis(1);

        assertThatThrownBy(() -> service.count(from, to, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("time range must not exceed 92 days");
    }

    @Test
    void acceptsRangeExactlyAtLimit() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = from.plus(Duration.ofDays(92));

        TenantContext.setTenantId("tenant-a");
        try {
            assertThatCode(() -> service.count(from, to, null)).doesNotThrowAnyException();
        } finally {
            TenantContext.clear();
        }
    }
}
