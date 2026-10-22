package com.processing.service;

import com.processing.common.dto.authorization.*;
import com.processing.common.dto.transactionlogger.TransactionRequest;
import com.processing.exception.AuthorizationException;
import com.processing.exception.UnknownBinException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RouteServiceTest {
    @Mock private RoutingService routing;
    @Mock private AuthorizationClient authorization;
    @Mock private AcquiringFeeClient fees;
    @Mock private LoggerClient logger;
    private RouteService service;

    @BeforeEach
    void setUp() {
        service = new RouteService(routing, authorization, fees, logger);
    }

    @Test
    void shouldPublishEnrichedTransactionWhenAuthorizationSucceeds() {
        // Arrange
        var request = request("4000001234567890");
        var approved = approved();
        when(routing.getIssuerIdByPan(request.pan())).thenReturn("ISS001");
        when(authorization.authorize(any())).thenReturn(approved);
        when(fees.fetchAcquiringFee(any(), any(), any(), any(), any())).thenReturn(new BigDecimal("2.50"));
        when(logger.log(any())).thenReturn(true);
        // Act
        var response = service.route(request);
        // Assert
        assertThat(response).isSameAs(approved);
        verify(logger).log(argThat(tx -> tx.issuerId().equals("ISS001")
            && tx.terminalId().equals("TERM0000") && tx.merchantId().equals("SHOP00000000000")
            && tx.acquiringFee().compareTo(new BigDecimal("2.50")) == 0
            && tx.rrn().equals("123456789012") && tx.processingTimeMs() == 0));
        verify(authorization, never()).rollback(any(), any());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"APPROVED", "DECLINED"})
    void shouldReturnSystemErrorWhenLoggingFailsAfterApproval(String rollbackStatus) {
        // Arrange
        var request = request("4000001234567890");
        when(routing.getIssuerIdByPan(request.pan())).thenReturn("ISS001");
        when(authorization.authorize(any())).thenReturn(approved());
        when(authorization.rollback(any(), any())).thenReturn(rollbackResponse(rollbackStatus));
        // Act
        var response = service.route(request);
        // Assert
        assertThat(response.responseCode()).isEqualTo("96");
        assertThat(response.status()).isEqualTo("DECLINED");
        verify(authorization).rollback(argThat(r -> r.issuerId().equals("ISS001")), eq("123456789012"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"123", "9999991234567890"})
    void shouldDeclineWithoutRollbackWhenBinIsUnknown(String pan) {
        // Arrange
        when(routing.getIssuerIdByPan(pan)).thenThrow(new UnknownBinException("unknown"));
        // Act
        var response = service.route(request(pan));
        // Assert
        assertThat(response.responseCode()).isEqualTo("14");
        verify(logger).log(any(TransactionRequest.class));
        verify(authorization, never()).rollback(any(), any());
    }

    @Test
    void shouldDeclineWhenAuthorizationIsUnavailable() {
        // Arrange
        when(routing.getIssuerIdByPan(any())).thenReturn("ISS001");
        when(authorization.authorize(any())).thenThrow(new AuthorizationException("000001", 1, "offline"));
        // Act
        var response = service.route(request("4000001234567890"));
        // Assert
        assertThat(response.declineReason()).isEqualTo("Authorization service unavailable");
        verify(logger).log(any(TransactionRequest.class));
        verify(authorization, never()).rollback(any(), any());
    }

    @Test
    void shouldPreserveDeclineWhenPublishingFails() {
        // Arrange
        when(routing.getIssuerIdByPan(any())).thenReturn("ISS001");
        var declined = AuthorizationResponse.unknownBin("000001");
        when(authorization.authorize(any())).thenReturn(declined);
        // Act
        var response = service.route(request("4000001234567890"));
        // Assert
        assertThat(response).isSameAs(declined);
        verify(authorization, never()).rollback(any(), any());
    }

    private AuthorizationResponse approved() {
        return new AuthorizationResponse("0110", "000001", "123456789012", "ABC123", "00", "APPROVED", null, null);
    }

    private RollbackResponse rollbackResponse(String status) {
        if (status == null) {
            return null;
        }
        return new RollbackResponse("123456789012", "00", status, null, 0);
    }

    private AuthorizationRequest request(String pan) {
        return new AuthorizationRequest("0100", "000001", pan, "000000", new BigDecimal("100"), "643",
            Instant.parse("2026-10-01T00:00:00Z"), "TERM", "POS", "SHOP", "5045", "ACQ001", null);
    }
}
