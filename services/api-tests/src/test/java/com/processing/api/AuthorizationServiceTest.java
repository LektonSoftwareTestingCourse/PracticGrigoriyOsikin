package com.processing.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static com.processing.api.ApiSupport.AUTH;
import static com.processing.api.ApiSupport.CARDS;
import static com.processing.api.ApiSupport.CARD_SERVICE;
import static com.processing.api.ApiSupport.authorization;
import static com.processing.api.ApiSupport.createCard;
import static com.processing.api.ApiSupport.randomDigits;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

class AuthorizationServiceTest {
    private static final String AUTHORIZE = "/api/internal/authorize";
    private static final String ROLLBACK = "/api/internal/rollback";

    // AuthControllerImpl maps DeclineOutcome reasons into HTTP statuses.
    @ParameterizedTest
    @CsvSource({
        "BLOCKED,1000,1000,1000,403,05,CARD_BLOCKED",
        "INACTIVE,1000,1000,1000,403,05,CARD_INACTIVE",
        "EXPIRED,1000,1000,1000,403,54,CARD_EXPIRED",
        "ACTIVE,99,1000,1000,422,51,INSUFFICIENT_FUNDS",
        "ACTIVE,1000,99,1000,400,61,EXCEEDS_AMOUNT_LIMIT"
    })
    void authorizationDeclinesBasedOnIndependentCardConditions(String status, int balance, int daily,
            int monthly, int http, String code, String reason) {
        var payment = authorization(createCard(balance, daily, monthly, status), 100);

        assertDecline(payment, http, code, reason);
    }

    @Test
    void monthlyLimitAccumulatesAcrossDays() {
        String pan = createCard(1000, 100, 100, "ACTIVE");
        var first = authorization(pan, 100);
        first.put("transmissionDateTime", "2024-03-01T12:00:00Z");

        given().spec(AUTH).body(first)
                .when().post(AUTHORIZE)
                .then().statusCode(200).body("responseCode", equalTo("00"));

        var next = authorization(pan, 100);
        next.put("transmissionDateTime", "2024-03-02T12:00:00Z");

        assertDecline(next, 400, "61", "EXCEEDS_AMOUNT_LIMIT");
    }

    @Test
    void activeCardDeclinesWhenTransmissionDateIsAfterExpiry() {
        var payment = authorization(createCard(1000, 1000, 1000, "ACTIVE"), 100);
        payment.put("transmissionDateTime", Instant.now().plus(3650, ChronoUnit.DAYS).toString());

        assertDecline(payment, 403, "54", "CARD_EXPIRED");
    }

    @Test
    void unknownPanWithinKnownBinIsRejectedByAuthorization() {
        String pan = createCard(1000, 1000, 1000, "ACTIVE");
        given().spec(CARD_SERVICE).when().delete(CARDS + "/{pan}", pan).then().statusCode(204);

        assertDecline(authorization(pan, 100), 404, "14", "CARD_NOT_FOUND");
    }

    @Test
    void rollbackRestoresBalanceAndCannotCreditCardTwice() {
        String pan = createCard(1000, 1000, 1000, "ACTIVE");
        var payment = authorization(pan, 100);
        String rrn = given().spec(AUTH).body(payment)
                .when().post(AUTHORIZE)
                .then().statusCode(200).body("responseCode", equalTo("00")).extract().path("rrn");
        var rollback = Map.of("pan", pan, "rrn", rrn, "amount", 100);
        assertBalance(pan, 900);

        given().spec(AUTH).body(rollback)
                .when().post(ROLLBACK)
                .then().statusCode(200).body("responseCode", equalTo("00"))
                .body("rrn", equalTo(rrn));
        assertBalance(pan, 1000);

        given().spec(AUTH).body(rollback)
                .when().post(ROLLBACK)
                .then().statusCode(409)
                .body("responseCode", equalTo("05"))
                .body("declineReason", equalTo("ALREADY_ROLLED_BACK"));
        assertBalance(pan, 1000);
    }

    @Test
    void rollbackOfUnknownReservationReturnsNotFound() {
        String pan = createCard(1000, 1000, 1000, "ACTIVE");
        String rrn = randomDigits(12);

        given().spec(AUTH).body(Map.of("pan", pan, "rrn", rrn, "amount", 100))
                .when().post(ROLLBACK)
                .then().statusCode(404)
                .body("rrn", equalTo(rrn))
                .body("responseCode", equalTo("14"))
                .body("declineReason", equalTo("TRANSACTION_NOT_FOUND"));
    }

    private static void assertDecline(Map<String, Object> payment, int http, String code, String reason) {
        given().spec(AUTH).body(payment)
                .when().post(AUTHORIZE)
                .then().statusCode(http)
                .body("stan", equalTo(payment.get("stan")))
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo(code))
                .body("declineReason", equalTo(reason))
                .body("rrn", nullValue())
                .body("authCode", nullValue());
    }

    private static void assertBalance(String pan, int balance) {
        given().spec(CARD_SERVICE)
                .when().get(CARDS + "/{pan}", pan)
                .then().statusCode(200).body("availableBalance.toInteger()", equalTo(balance));
    }
}
