package com.processing.authorization.services;

import com.processing.authorization.client.*;
import com.processing.authorization.events.*;
import com.processing.authorization.exceptions.*;
import com.processing.common.dto.authorization.RollbackRequest;
import org.springframework.dao.DuplicateKeyException;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import java.util.stream.Stream;
import com.processing.authorization.repositories.LimitUsageRepository;
import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.cardmanagement.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import java.time.*;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {
    private static final String PAN = "4000001234567890";
    @Mock private LimitUsageRepository limits;
    @Mock private AuthorizationEventNotifier events;
    @Mock private BinLookupClient bins;
    @Mock private CardManagementClient cards;
    private AuthServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AuthServiceImpl(limits, events, bins, cards);
    }

    @ParameterizedTest
    @CsvSource({"99.99", "100.00"})
    void shouldApproveWhenAmountDoesNotExceedBalance(BigDecimal amount) {
        // Arrange
        when(cards.getCard(PAN)).thenReturn(card(CardModelStatus.ACTIVE, YearMonth.of(2026, 10)));
        when(bins.getIssuerId(PAN)).thenReturn(Optional.of("ISS001"));
        when(limits.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(1);
        // Act
        var response = service.authorize(request(amount), Instant.now());
        // Assert
        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(response.responseCode()).isEqualTo("00");
        assertThat(response.authCode()).matches("[0-9A-Z]{6}");
        assertThat(response.rrn()).matches("[0-9]{12}");
        verify(cards).reserve(eq(amount), eq(response.rrn()), eq(PAN));
        verify(events).notify(any(AuthServiceAuthorizeEvent.class));
    }

    @ParameterizedTest
    @CsvSource({"BLOCKED, CARD_BLOCKED, 05", "EXPIRED, CARD_EXPIRED, 54", "INACTIVE, CARD_INACTIVE, 05"})
    void shouldDeclineWhenCardIsNotActive(CardModelStatus status, String reason, String code) {
        // Arrange
        when(cards.getCard(PAN)).thenReturn(card(status, YearMonth.of(2026, 10)));
        when(bins.getIssuerId(PAN)).thenReturn(Optional.empty());
        // Act
        var response = service.authorize(request(new BigDecimal("100")), Instant.now());
        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.declineReason()).isEqualTo(reason);
        assertThat(response.responseCode()).isEqualTo(code);
        verify(cards, never()).reserve(any(), any(), any());
        verify(events).notify(any(AuthServiceAuthDeclinedCardStatusEvent.class));
    }

    @Test
    void shouldDeclineWhenCardIsMissing() {
        // Arrange
        when(cards.getCard(PAN)).thenThrow(new CardNotFoundException(PAN));
        // Act
        var response = service.authorize(request(BigDecimal.ONE), Instant.now());
        // Assert
        assertThat(response.declineReason()).isEqualTo("CARD_NOT_FOUND");
        assertThat(response.responseCode()).isEqualTo("14");
        verify(events).notify(any(AuthServiceAuthDeclineNoCardEvent.class));
        verify(cards, never()).reserve(any(), any(), any());
    }

    @Test
    void shouldDeclineWhenAmountExceedsBalanceByOneCent() {
        // Arrange
        when(cards.getCard(PAN)).thenReturn(card(CardModelStatus.ACTIVE, YearMonth.of(2026, 10)));
        when(bins.getIssuerId(PAN)).thenReturn(Optional.empty());
        // Act
        var response = service.authorize(request(new BigDecimal("100.01")), Instant.now());
        // Assert
        assertThat(response.declineReason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(response.responseCode()).isEqualTo("51");
        verify(cards, never()).reserve(any(), any(), any());
    }

    @Test
    void shouldDeclineWhenExpiryMonthHasPassed() {
        // Arrange
        when(cards.getCard(PAN)).thenReturn(card(CardModelStatus.ACTIVE, YearMonth.of(2026, 9)));
        when(bins.getIssuerId(PAN)).thenReturn(Optional.empty());
        // Act
        var response = service.authorize(request(BigDecimal.ONE), Instant.now());
        // Assert
        assertThat(response.declineReason()).isEqualTo("CARD_EXPIRED");
        assertThat(response.responseCode()).isEqualTo("54");
        verify(cards, never()).reserve(any(), any(), any());
    }

    @Test
    void shouldDeclineWhenLimitUpdateIsRejected() {
        // Arrange
        when(cards.getCard(PAN)).thenReturn(card(CardModelStatus.ACTIVE, YearMonth.of(2026, 10)));
        when(bins.getIssuerId(PAN)).thenReturn(Optional.empty());
        when(limits.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(0);
        // Act
        var response = service.authorize(request(BigDecimal.ONE), Instant.now());
        // Assert
        assertThat(response.declineReason()).isEqualTo("EXCEEDS_AMOUNT_LIMIT");
        assertThat(response.responseCode()).isEqualTo("61");
        verify(cards, never()).reserve(any(), any(), any());
    }

    @Test
    void shouldDeclineWhenCardManagementIsUnavailable() {
        // Arrange
        when(cards.getCard(PAN)).thenThrow(new ServiceUnavailableException("offline"));
        // Act
        var response = service.authorize(request(BigDecimal.ONE), Instant.now());
        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo("96");
        assertThat(response.declineReason()).isEqualTo("SERVICE_UNAVAILABLE");
        verify(cards, never()).reserve(any(), any(), any());
        verify(events).notify(any(AuthServiceAuthDeclineNoServiceEvent.class));
    }

    @Test
    void shouldDeclineWhenCardStatusIsMissing() {
        // Arrange
        when(cards.getCard(PAN)).thenReturn(card(null, YearMonth.of(2026, 10)));
        when(bins.getIssuerId(PAN)).thenReturn(Optional.empty());
        // Act
        var response = service.authorize(request(BigDecimal.ONE), Instant.now());
        // Assert
        assertThat(response.declineReason()).isEqualTo("UNKNOWN_REASON");
        verify(cards, never()).reserve(any(), any(), any());
        verify(events).notify(any(AuthServiceAuthDeclineUnknownEvent.class));
    }

    @Test
    void shouldApproveWhenLimitUpdateSucceedsAfterDuplicateKey() {
        // Arrange
        stubActiveCard();
        when(limits.upsertLimitUsage(any(), any(), any(), any(), any()))
            .thenThrow(new DuplicateKeyException("concurrent insert")).thenReturn(1);
        // Act
        var response = service.authorize(request(BigDecimal.ONE), Instant.now());
        // Assert
        assertThat(response.status()).isEqualTo("APPROVED");
        verify(cards).reserve(eq(BigDecimal.ONE), eq(response.rrn()), eq(PAN));
        verify(events).notify(any(AuthServiceDuplicateKeyEvent.class));
        verify(events).notify(any(AuthServiceAuthorizeEvent.class));
    }

    @Test
    void shouldDeclineWhenLimitRetryFails() {
        // Arrange
        stubActiveCard();
        when(limits.upsertLimitUsage(any(), any(), any(), any(), any()))
            .thenThrow(new DuplicateKeyException("concurrent insert"))
            .thenThrow(new IllegalStateException("database unavailable"));
        // Act
        var response = service.authorize(request(BigDecimal.ONE), Instant.now());
        // Assert
        assertThat(response.responseCode()).isEqualTo("96");
        assertThat(response.declineReason()).isEqualTo("DB_UNAVAILABLE");
        verify(cards, never()).reserve(any(), any(), any());
        verify(events).notify(any(AuthServiceAuthDeclineNoServiceEvent.class));
    }

    @Test
    void shouldDeclineWhenLimitDatabaseIsUnavailable() {
        // Arrange
        stubActiveCard();
        when(limits.upsertLimitUsage(any(), any(), any(), any(), any()))
            .thenThrow(new IllegalStateException("database unavailable"));
        // Act
        var response = service.authorize(request(BigDecimal.ONE), Instant.now());
        // Assert
        assertThat(response.declineReason()).isEqualTo("DB_UNAVAILABLE");
        verify(cards, never()).reserve(any(), any(), any());
        verify(events).notify(any(AuthServiceAuthDeclineNoServiceEvent.class));
    }

    @ParameterizedTest
    @MethodSource("reservationFailures")
    void shouldDeclineWhenReservationFails(RuntimeException failure, String reason, String code) {
        // Arrange
        stubActiveCard();
        when(limits.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(1);
        doThrow(failure).when(cards).reserve(any(), any(), eq(PAN));
        // Act
        var response = service.authorize(request(BigDecimal.ONE), Instant.now());
        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.declineReason()).isEqualTo(reason);
        assertThat(response.responseCode()).isEqualTo(code);
        assertThat(response.authCode()).isNull();
        verify(events, never()).notify(any(AuthServiceAuthorizeEvent.class));
    }

    @Test
    void shouldApproveRollbackWhenCardManagementSucceeds() {
        // Arrange
        var request = new RollbackRequest("123456789012", PAN, BigDecimal.TEN);
        // Act
        var response = service.rollback(request, Instant.now());
        // Assert
        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(response.responseCode()).isEqualTo("00");
        assertThat(response.rrn()).isEqualTo(request.rrn());
        verify(cards).rollback(request);
        verify(events).notify(any(AuthServiceRollbackEvent.class));
    }

    @ParameterizedTest
    @MethodSource("rollbackFailures")
    void shouldDeclineRollbackWhenCardManagementFails(RuntimeException failure, String reason, String code) {
        // Arrange
        var request = new RollbackRequest("123456789012", PAN, BigDecimal.TEN);
        doThrow(failure).when(cards).rollback(request);
        // Act
        var response = service.rollback(request, Instant.now());
        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo(code);
        assertThat(response.declineReason()).isEqualTo(reason);
        assertThat(response.rrn()).isEqualTo(request.rrn());
        verify(events, never()).notify(any(AuthServiceRollbackEvent.class));
    }

    private static Stream<Arguments> reservationFailures() {
        return Stream.of(
            Arguments.of(new CardNotFoundException(PAN), "CARD_NOT_FOUND", "14"),
            Arguments.of(new ServiceUnavailableException("offline"), "SERVICE_UNAVAILABLE", "96"),
            Arguments.of(new InsufficientFundsException("spent concurrently"), "INSUFFICIENT_FUNDS", "51"),
            Arguments.of(new ReserveException("rejected"), "RESERVATION_FAILED", "96"),
            Arguments.of(new IllegalStateException("unexpected"), "UNKNOWN_REASON", "05"));
    }

    private static Stream<Arguments> rollbackFailures() {
        return Stream.of(
            Arguments.of(new CardNotFoundException(PAN), "TRANSACTION_NOT_FOUND", "14"),
            Arguments.of(new ServiceUnavailableException("offline"), "SERVICE_UNAVAILABLE", "96"),
            Arguments.of(new RollbackConflictException("already rolled back"), "ALREADY_ROLLED_BACK", "05"),
            Arguments.of(new RollbackFailureException("rejected"), "ROLLBACK_FAILED", "96"),
            Arguments.of(new IllegalStateException("unexpected"), "UNKNOWN_REASON", "05"));
    }

    private void stubActiveCard() {
        when(cards.getCard(PAN)).thenReturn(card(CardModelStatus.ACTIVE, YearMonth.of(2026, 10)));
        when(bins.getIssuerId(PAN)).thenReturn(Optional.empty());
    }

    private CardModel card(CardModelStatus status, YearMonth expiry) {
        return new CardModel(new UUID(0, 1), PAN, "400000", "IVAN IVANOV", expiry, status, "643",
            new BigDecimal("1000"), new BigDecimal("10000"), new BigDecimal("100"), "ISS001",
            Instant.parse("2026-01-01T00:00:00Z"));
    }

    private AuthorizationRequest request(BigDecimal amount) {
        return new AuthorizationRequest("0100", "000001", PAN, "000000", amount, "643",
            Instant.parse("2026-10-31T23:59:59Z"), "TERM0001", "POS", "MERCH0000000002", "5045", "ACQ001", "ISS001");
    }
}
