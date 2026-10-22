package com.processing.service;

import com.processing.common.dto.authorization.*;
import com.processing.config.SwitchProperties;
import com.processing.exception.AuthorizationException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthorizationClientTest {
    @Mock private RestClient rest;
    @Mock private RestClient.RequestBodyUriSpec body;
    @Mock private RestClient.ResponseSpec response;
    private CircuitBreaker breaker;
    private AuthorizationClient client;

    @BeforeEach
    void setUp() {
        var properties = new SwitchProperties("test", null, "http://auth", null, null, null,
            new SwitchProperties.RetryProperties(1, List.of()), null);
        breaker = CircuitBreaker.ofDefaults("test");
        client = new AuthorizationClient(properties, rest,
            Retry.of("test", RetryConfig.custom().maxAttempts(1).build()), breaker);
    }

    private void stubPost() {
        when(rest.post()).thenReturn(body);
        when(body.uri(anyString())).thenReturn(body);
        when(body.body(any(Object.class))).thenReturn(body);
        when(body.retrieve()).thenReturn(response);
        when(response.onStatus(any(), any())).thenReturn(response);
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "DECLINED"})
    void shouldReturnAuthorizationWhenResponseHasValidStatus(String status) {
        // Arrange
        stubPost();
        var expected = authorizationResponse(status);
        when(response.body(AuthorizationResponse.class)).thenReturn(expected);
        // Act
        var actual = client.authorize(request());
        // Assert
        assertThat(actual).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"UNKNOWN"})
    void shouldFailAuthorizationWhenResponseHasInvalidStatus(String status) {
        // Arrange
        stubPost();
        when(response.body(AuthorizationResponse.class)).thenReturn(authorizationResponse(status));
        // Act / Assert
        assertThatThrownBy(() -> client.authorize(request())).isInstanceOf(AuthorizationException.class);
    }

    @Test
    void shouldFailAuthorizationWhenResponseBodyIsAbsent() {
        // Arrange
        stubPost();
        when(response.body(AuthorizationResponse.class)).thenReturn(null);
        // Act / Assert
        assertThatThrownBy(() -> client.authorize(request())).isInstanceOf(AuthorizationException.class);
    }

    @Test
    void shouldFailFastWhenCircuitIsOpen() {
        // Arrange
        breaker.transitionToOpenState();
        // Act / Assert
        assertThatThrownBy(() -> client.authorize(request())).isInstanceOf(AuthorizationException.class)
            .hasMessageContaining("Circuit breaker open");
        verifyNoInteractions(rest);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldWrapFailureWhenTransportThrows(boolean nested) {
        // Arrange
        var failure = new IllegalStateException("offline");
        when(rest.post()).thenThrow(transportFailure(nested, failure));
        // Act / Assert
        assertThatThrownBy(() -> client.authorize(request())).isInstanceOf(AuthorizationException.class)
            .hasMessageContaining("offline");
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "DECLINED"})
    void shouldReturnRollbackWhenResponseHasValidStatus(String status) {
        // Arrange
        stubPost();
        var expected = new RollbackResponse("rrn", "00", status, null, 0);
        when(response.body(RollbackResponse.class)).thenReturn(expected);
        // Act
        var actual = client.rollback(request(), "rrn");
        // Assert
        assertThat(actual).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"UNKNOWN"})
    void shouldReturnNullWhenRollbackResponseHasInvalidStatus(String status) {
        // Arrange
        stubPost();
        when(response.body(RollbackResponse.class)).thenReturn(new RollbackResponse("rrn", "00", status, null, 0));
        // Act
        var actual = client.rollback(request(), "rrn");
        // Assert
        assertThat(actual).isNull();
    }

    @Test
    void shouldReturnNullWhenRollbackBodyIsAbsent() {
        // Arrange
        stubPost();
        when(response.body(RollbackResponse.class)).thenReturn(null);
        // Act
        var actual = client.rollback(request(), "rrn");
        // Assert
        assertThat(actual).isNull();
    }

    @Test
    void shouldReturnNullWhenRollbackTransportFails() {
        // Arrange
        when(rest.post()).thenThrow(new IllegalStateException("offline"));
        // Act
        var actual = client.rollback(request(), "rrn");
        // Assert
        assertThat(actual).isNull();
    }

    @Test
    void shouldReportDownWhenHealthTransportFails() {
        // Arrange
        when(rest.get()).thenThrow(new IllegalStateException("offline"));
        // Act
        var status = client.checkHealth();
        // Assert
        assertThat(status).isEqualTo("down");
    }

    @Test
    void shouldReportOkWhenHealthResponds() {
        // Arrange
        var get = mock(RestClient.RequestHeadersUriSpec.class);
        when(rest.get()).thenReturn(get);
        when(get.uri(anyString())).thenReturn(get);
        when(get.retrieve()).thenReturn(response);
        // Act
        var status = client.checkHealth();
        // Assert
        assertThat(status).isEqualTo("ok");
    }

    private RuntimeException transportFailure(boolean nested, RuntimeException failure) {
        return nested ? new IllegalStateException("wrapper", failure) : failure;
    }

    private AuthorizationResponse authorizationResponse(String status) {
        return new AuthorizationResponse("0110", "000001", "rrn", "ABC123", "00", status, null, 0);
    }

    private AuthorizationRequest request() {
        return new AuthorizationRequest("0100", "000001", "4000001234567890", "000000", BigDecimal.TEN, "643",
            Instant.parse("2026-10-01T00:00:00Z"), "TERM0001", "POS", "SHOP00000000000", "5045", "ACQ001", "ISS001");
    }
}
