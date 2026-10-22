package com.processing.gateway.ratelimit;

import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.assertThat;

class InMemoryRateLimiterTest {
    @ParameterizedTest
    @CsvSource({"0, false", "-1, false", "1, true"})
    void shouldAllowRequestWhenCapacityHasToken(int capacity, boolean expected) {
        // Arrange
        var limiter = InMemoryRateLimiter.forTesting(capacity, 1, Duration.ofMinutes(10), 10, () -> 0L);
        // Act
        boolean allowed = limiter.allowRequest("client");
        // Assert
        assertThat(allowed).isEqualTo(expected);
    }

    @Test
    void shouldRejectRequestWhenBucketIsExhausted() {
        // Arrange
        var limiter = InMemoryRateLimiter.forTesting(1, 0, Duration.ofMinutes(10), 10, () -> 0L);
        limiter.allowRequest("client");
        // Act
        boolean allowed = limiter.allowRequest("client");
        // Assert
        assertThat(allowed).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"499999999, false", "500000000, true", "1000000000, true"})
    void shouldRefillTokenWhenEnoughTimeElapses(long elapsedNanos, boolean expected) {
        // Arrange
        var nanos = new AtomicLong();
        Ticker ticker = nanos::get;
        var limiter = InMemoryRateLimiter.forTesting(1, 2, Duration.ofMinutes(10), 10, ticker);
        limiter.allowRequest("client");
        nanos.set(elapsedNanos);
        // Act
        boolean allowed = limiter.allowRequest("client");
        // Assert
        assertThat(allowed).isEqualTo(expected);
    }

    @Test
    void shouldUseIndependentBucketWhenClientChanges() {
        // Arrange
        var limiter = InMemoryRateLimiter.forTesting(1, 0, Duration.ofMinutes(10), 10, () -> 0L);
        limiter.allowRequest("first");
        // Act
        boolean allowed = limiter.allowRequest("second");
        // Assert
        assertThat(allowed).isTrue();
    }
    @Test
    void shouldCapRefilledTokensWhenLongTimeElapses() {
        // Arrange
        var nanos = new AtomicLong();
        var limiter = InMemoryRateLimiter.forTesting(1, 2, Duration.ofMinutes(10), 10, nanos::get);
        limiter.allowRequest("client");
        nanos.set(Duration.ofSeconds(100).toNanos());
        limiter.allowRequest("client");
        // Act
        boolean allowed = limiter.allowRequest("client");
        // Assert
        assertThat(allowed).isFalse();
    }

    @Test
    void shouldStartFreshBucketWhenIdleBucketExpires() {
        // Arrange
        var nanos = new AtomicLong();
        var limiter = InMemoryRateLimiter.forTesting(1, 0, Duration.ofSeconds(10), 10, nanos::get);
        limiter.allowRequest("client");
        nanos.set(Duration.ofSeconds(10).toNanos());
        limiter.cleanUp();
        // Act
        boolean allowed = limiter.allowRequest("client");
        // Assert
        assertThat(allowed).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"0", "-1"})
    void shouldNotRefillWhenRefillRateIsNonPositive(double refillRate) {
        // Arrange
        var nanos = new AtomicLong();
        var limiter = InMemoryRateLimiter.forTesting(1, refillRate, Duration.ofMinutes(10), 10, nanos::get);
        limiter.allowRequest("client");
        nanos.set(Duration.ofSeconds(1).toNanos());
        // Act
        boolean allowed = limiter.allowRequest("client");
        // Assert
        assertThat(allowed).isFalse();
    }
}
