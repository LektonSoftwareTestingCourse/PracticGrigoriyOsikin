package com.processing.gateway.circuitbreaker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class InMemoryCircuitBreakerTest {
    private static final String SERVICE = "authorization";
    private static final Instant START = Instant.parse("2026-10-01T00:00:00Z");
    private final AtomicReference<Instant> now = new AtomicReference<>(START);
    private InMemoryCircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        var clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(call -> now.get());
        breaker = InMemoryCircuitBreaker.forTesting(Duration.ofSeconds(10), 3, clock);
    }

    @Test
    void shouldAllowRequestWhenNoFailuresHaveOccurred() {
        // Act
        boolean allowed = breaker.allowRequest(SERVICE);
        // Assert
        assertThat(allowed).isTrue();
    }

    @Test
    void shouldAllowRequestWhenFailureThresholdIsNotReached() {
        // Arrange
        breaker.recordFailure(SERVICE);
        breaker.recordFailure(SERVICE);
        // Act
        boolean allowed = breaker.allowRequest(SERVICE);
        // Assert
        assertThat(allowed).isTrue();
    }

    @Test
    void shouldRejectRequestWhenFailureThresholdIsReached() {
        // Arrange
        openCircuit();
        // Act
        boolean allowed = breaker.allowRequest(SERVICE);
        // Assert
        assertThat(allowed).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"9999, false", "10000, true", "10001, true"})
    void shouldAllowProbeWhenOpenIntervalExpires(long elapsedMillis, boolean expected) {
        // Arrange
        openCircuit();
        now.set(START.plusMillis(elapsedMillis));
        // Act
        boolean allowed = breaker.allowRequest(SERVICE);
        // Assert
        assertThat(allowed).isEqualTo(expected);
    }

    @Test
    void shouldRejectSecondProbeWhenFirstProbeIsInProgress() {
        // Arrange
        openCircuit();
        now.set(START.plusSeconds(10));
        breaker.allowRequest(SERVICE);
        // Act
        boolean allowed = breaker.allowRequest(SERVICE);
        // Assert
        assertThat(allowed).isFalse();
    }

    @Test
    void shouldKeepOtherServiceAvailableWhenOneCircuitIsOpen() {
        // Arrange
        openCircuit();
        // Act
        boolean allowed = breaker.allowRequest("card-management");
        // Assert
        assertThat(allowed).isTrue();
    }

    @Test
    void shouldResetFailureCountWhenCallSucceeds() {
        // Arrange
        breaker.recordFailure(SERVICE);
        breaker.recordFailure(SERVICE);
        breaker.recordSuccess(SERVICE);
        breaker.recordFailure(SERVICE);
        // Act
        boolean allowed = breaker.allowRequest(SERVICE);
        // Assert
        assertThat(allowed).isTrue();
    }

    @Test
    void shouldCloseCircuitWhenProbeSucceeds() {
        // Arrange
        openCircuit();
        now.set(START.plusSeconds(10));
        breaker.allowRequest(SERVICE);
        // Act
        breaker.recordSuccess(SERVICE);
        // Assert
        assertThat(breaker.allowRequest(SERVICE)).isTrue();
        assertThat(breaker.allowRequest(SERVICE)).isTrue();
    }

    @Test
    void shouldRestartOpenIntervalWhenProbeFails() {
        // Arrange
        openCircuit();
        now.set(START.plusSeconds(10));
        breaker.allowRequest(SERVICE);
        now.set(START.plusSeconds(12));
        breaker.recordFailure(SERVICE);
        now.set(START.plusSeconds(20));
        // Act
        boolean allowed = breaker.allowRequest(SERVICE);
        // Assert
        assertThat(allowed).isFalse();
    }

    private void openCircuit() {
        breaker.recordFailure(SERVICE);
        breaker.recordFailure(SERVICE);
        breaker.recordFailure(SERVICE);
    }
}
