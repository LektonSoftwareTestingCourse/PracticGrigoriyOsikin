package com.processing.cardmanagement.services;

import com.processing.cardmanagement.events.*;
import com.processing.cardmanagement.exceptions.*;
import com.processing.cardmanagement.models.*;
import com.processing.cardmanagement.options.*;
import com.processing.cardmanagement.repositories.*;
import com.processing.cardmanagement.services.retries.RetryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CardServiceImplTest {
    private static final String PAN = "4000001234567890";
    @Mock private CardRepository cards;
    @Mock private ReservationRepository reservations;
    @Mock private ReservationRollbackRepository rollbacks;
    @Mock private CardServiceSettings settings;
    @Mock private CardServiceDefaults defaults;
    @Mock private PanGenerator pans;
    @Mock private CardEventNotifier events;
    @Mock private BinIssuerService bins;
    @Mock private RetryService retries;
    @Mock private TransactionRunner transactions;
    private CardServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CardServiceImpl(cards, reservations, rollbacks, settings, defaults,
            pans, events, bins, retries, transactions);
    }

    @Test
    void shouldReturnCardWhenPanExists() {
        // Arrange
        var card = activeCard();
        when(cards.findByPan(PAN)).thenReturn(Optional.of(card));
        // Act
        var result = service.getCard(PAN);
        // Assert
        assertThat(result).isEqualTo(card);
    }

    @Test
    void shouldRejectLookupWhenCardIsMissing() {
        // Arrange
        when(cards.findByPan(PAN)).thenReturn(Optional.empty());
        // Act / Assert
        assertThatThrownBy(() -> service.getCard(PAN)).isInstanceOf(CardNotFoundException.class);
    }

    @ParameterizedTest
    @CsvSource({"99.99, 0.01", "100.00, 0.00"})
    void shouldReserveWhenBalanceCoversAmount(BigDecimal amount, BigDecimal remaining) {
        // Arrange
        executeTransactions();
        when(cards.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard()));
        when(reservations.isUnique("rrn", PAN)).thenReturn(true);
        when(reservations.save(any())).thenAnswer(call -> call.getArgument(0));
        when(cards.update(any())).thenAnswer(call -> call.getArgument(0));
        // Act
        var result = service.reserve(PAN, amount, "rrn");
        // Assert
        assertThat(result.availableBalance()).isEqualByComparingTo(remaining);
        verify(reservations).save(argThat(r -> r.pan().equals(PAN)
            && r.reservationAmount().compareTo(amount) == 0 && r.rrn().equals("rrn")));
        verify(cards).update(result);
        verify(events).notifyListeners(new CardServiceReserveEvent(PAN, "rrn", amount));
    }

    @Test
    void shouldRejectReservationWhenFundsAreInsufficient() {
        // Arrange
        executeTransactions();
        when(cards.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard()));
        // Act / Assert
        assertThatThrownBy(() -> service.reserve(PAN, new BigDecimal("100.01"), "rrn"))
            .isInstanceOf(InsufficientFundsException.class);
        verify(cards, never()).update(any());
        verifyNoInteractions(events);
    }

    @ParameterizedTest
    @EnumSource(value = CardStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "ACTIVE")
    void shouldRejectReservationWhenCardIsNotActive(CardStatus status) {
        // Arrange
        executeTransactions();
        var inactive = activeCard().withData(status, BigDecimal.TEN, new BigDecimal("1000"), new BigDecimal("100"));
        when(cards.findByPanForUpdate(PAN)).thenReturn(Optional.of(inactive));
        // Act / Assert
        assertThatThrownBy(() -> service.reserve(PAN, BigDecimal.ONE, "rrn")).isInstanceOf(IllegalStateException.class);
        verify(cards, never()).update(any());
        verifyNoInteractions(events);
    }

    @Test
    void shouldRejectReservationWhenRrnAlreadyExists() {
        // Arrange
        executeTransactions();
        when(cards.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard()));
        when(reservations.isUnique("rrn", PAN)).thenReturn(false);
        // Act / Assert
        assertThatThrownBy(() -> service.reserve(PAN, BigDecimal.ONE, "rrn"))
            .isInstanceOf(ReservationAlreadyExistsException.class);
        verify(reservations, never()).save(any());
        verify(cards, never()).update(any());
    }

    @ParameterizedTest
    @CsvSource({"99", "100"})
    void shouldReturnPageWhenLimitIsWithinMaximum(int limit) {
        // Arrange
        when(settings.maxPageLimit()).thenReturn(100);
        when(cards.findCards(limit, 0L, null, null, null, null, null)).thenReturn(List.of(activeCard()));
        // Act
        var result = service.getCards(limit, 0L, null, null, null, null, null);
        // Assert
        assertThat(result).containsExactly(activeCard());
    }

    @Test
    void shouldRejectPageWhenLimitExceedsMaximum() {
        // Arrange
        when(settings.maxPageLimit()).thenReturn(100);
        // Act / Assert
        assertThatThrownBy(() -> service.getCards(101, 0L, null, null, null, null, null))
            .isInstanceOf(TooLargeLimitException.class);
    }

    @Test
    void shouldUseDefaultPaginationWhenLimitAndOffsetAreAbsent() {
        // Arrange
        when(defaults.pageLimit()).thenReturn(20);
        when(defaults.pageOffset()).thenReturn(5L);
        when(cards.findCards(20, 5L, CardStatus.ACTIVE, "400000", "ISS001", null, null))
            .thenReturn(List.of(activeCard()));
        // Act
        var result = service.getCards(null, null, CardStatus.ACTIVE, "400000", "ISS001", null, null);
        // Assert
        assertThat(result).containsExactly(activeCard());
    }

    @Test
    void shouldPreserveCardFieldsWhenPatchValuesAreAbsent() {
        // Arrange
        executeTransactions();
        when(cards.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard()));
        when(cards.update(any())).thenAnswer(call -> call.getArgument(0));
        // Act
        var result = service.patchCard(PAN, null, null, null, null);
        // Assert
        assertThat(result).isEqualTo(activeCard());
        verify(cards).update(activeCard());
        verify(events).notifyListeners(new CardServicePatchEvent(PAN));
    }

    @Test
    void shouldUpdateCardWhenPatchValuesAreProvided() {
        // Arrange
        executeTransactions();
        when(cards.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard()));
        when(cards.update(any())).thenAnswer(call -> call.getArgument(0));
        var expected = activeCard().withData(CardStatus.BLOCKED, new BigDecimal("20"),
            new BigDecimal("2000"), new BigDecimal("200"));
        // Act
        var result = service.patchCard(PAN, CardStatus.BLOCKED, new BigDecimal("20"),
            new BigDecimal("2000"), new BigDecimal("200"));
        // Assert
        assertThat(result).isEqualTo(expected);
        verify(cards).update(expected);
        verify(events).notifyListeners(new CardServicePatchEvent(PAN));
    }

    @ParameterizedTest
    @CsvSource({"-1, 1000", "10, -1", "1001, 1000"})
    void shouldRejectPatchWhenLimitsAreInvalid(BigDecimal daily, BigDecimal monthly) {
        // Arrange
        executeTransactions();
        when(cards.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard()));
        // Act / Assert
        assertThatThrownBy(() -> service.patchCard(PAN, null, daily, monthly, null))
            .isInstanceOf(IllegalArgumentException.class);
        verify(cards, never()).update(any());
        verifyNoInteractions(events);
    }

    @Test
    void shouldRestoreBalanceWhenReservationIsRolledBack() {
        // Arrange
        executeTransactions();
        when(cards.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard()));
        var reservation = reservation(ReservationStatus.RESERVED);
        when(reservations.findByRrnAndPanForUpdate("rrn", PAN)).thenReturn(Optional.of(reservation));
        when(rollbacks.save(any())).thenAnswer(call -> call.getArgument(0));
        when(cards.update(any())).thenAnswer(call -> call.getArgument(0));
        // Act
        var result = service.rollback(PAN, BigDecimal.TEN, "rrn");
        // Assert
        assertThat(result.availableBalance()).isEqualByComparingTo("110");
        verify(rollbacks).save(argThat(r -> r.reservationId().equals(reservation.id())
            && r.rollbackAmount().compareTo(BigDecimal.TEN) == 0 && r.pan().equals(PAN) && r.rrn().equals("rrn")));
        verify(reservations).save(argThat(r -> r.id().equals(reservation.id()) && r.status() == ReservationStatus.ROLLED_BACK));
        verify(cards).update(result);
        verify(events).notifyListeners(new CardServiceRollbackEvent(PAN, "rrn", BigDecimal.TEN));
    }

    @Test
    void shouldRejectRollbackWhenReservationIsMissing() {
        // Arrange
        executeTransactions();
        when(cards.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard()));
        when(reservations.findByRrnAndPanForUpdate("rrn", PAN)).thenReturn(Optional.empty());
        // Act / Assert
        assertThatThrownBy(() -> service.rollback(PAN, BigDecimal.TEN, "rrn"))
            .isInstanceOf(ReservationNotFoundException.class);
        verifyNoInteractions(rollbacks, events);
        verify(cards, never()).update(any());
    }

    @Test
    void shouldRejectRollbackWhenReservationWasAlreadyRolledBack() {
        // Arrange
        executeTransactions();
        when(cards.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard()));
        when(reservations.findByRrnAndPanForUpdate("rrn", PAN))
            .thenReturn(Optional.of(reservation(ReservationStatus.ROLLED_BACK)));
        // Act / Assert
        assertThatThrownBy(() -> service.rollback(PAN, BigDecimal.TEN, "rrn"))
            .isInstanceOf(RollbackAlreadySatisfiedException.class);
        verifyNoInteractions(rollbacks, events);
        verify(cards, never()).update(any());
    }

    @Test
    void shouldRejectReservationWhenCardIsMissing() {
        // Arrange
        executeTransactions();
        when(cards.findByPanForUpdate(PAN)).thenReturn(Optional.empty());
        // Act / Assert
        assertThatThrownBy(() -> service.reserve(PAN, BigDecimal.ONE, "rrn"))
            .isInstanceOf(CardNotFoundException.class);
        verifyNoInteractions(reservations, events);
        verify(cards, never()).update(any());
    }

    private Reservation reservation(ReservationStatus status) {
        var createdAt = Instant.parse("2026-01-01T00:00:00Z");
        return new Reservation(new UUID(0, 2), PAN, BigDecimal.TEN, "rrn", status, createdAt, createdAt);
    }

    private void executeTransactions() {
        when(transactions.runSupplier(any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(0)).get());
    }

    private Card activeCard() {
        return new Card(new UUID(0, 1), PAN, "400000", "IVAN IVANOV", YearMonth.of(2030, 12), CardStatus.ACTIVE,
            "643", BigDecimal.TEN, new BigDecimal("1000"), new BigDecimal("100"), "ISS001",
            Instant.parse("2026-01-01T00:00:00Z"));
    }
}
