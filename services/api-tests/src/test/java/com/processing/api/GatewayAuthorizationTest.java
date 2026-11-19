package com.processing.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.Map;

import static com.processing.api.ApiSupport.GATEWAY;
import static com.processing.api.ApiSupport.SEARCH;
import static com.processing.api.ApiSupport.authorization;
import static com.processing.api.ApiSupport.createCard;
import static com.processing.api.ApiSupport.randomDigits;
import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;

class GatewayAuthorizationTest {
    private static final String TRANSACTIONS = "/api/transactions";

    @Test
    void approvedPaymentIsFindableByPanAndStan() {
        var payment = authorization(createCard(1000, 1000, 1000, "ACTIVE"), 100);
        String stan = (String) payment.get("stan");

        // AuthorizationResponse and AuthServiceImpl define the generated RRN and authorization code.
        var response = given().spec(GATEWAY).body(payment)
                .when().post(TRANSACTIONS)
                .then().statusCode(200).contentType("application/json")
                .body(matchesJsonSchemaInClasspath("authorization-response.json"))
                .body("stan", equalTo(stan))
                // Known difference from the 0110 example in docs/api-spec.md.
                .body("mti", equalTo("0100"))
                .body("status", equalTo("APPROVED"))
                .body("responseCode", equalTo("00"))
                .body("rrn", matchesPattern("[0-9]{12}"))
                .body("authCode", matchesPattern("[A-Z0-9]{6}"))
                .body("declineReason", nullValue())
                .body("processingTimeMs", greaterThanOrEqualTo(0))
                .extract();

        String rrn = response.path("rrn");
        String authCode = response.path("authCode");
        String matching = "transactions.findAll { it.stan == '" + stan + "' }";
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> given().spec(GATEWAY).queryParam("pan", payment.get("pan"))
                        .when().get(SEARCH)
                        .then().statusCode(200)
                        .body(matching, hasSize(1))
                        .body(matching + "[0].rrn", equalTo(rrn))
                        .body(matching + "[0].authCode", equalTo(authCode))
                        .body(matching + "[0].status", equalTo("APPROVED")));
    }

    @ParameterizedTest
    @CsvSource({
        "mti,0400,must be '0100'",
        "pan,400000123456789,must be exactly 16 digits",
        "pan,40000012345678901,must be exactly 16 digits",
        "pan,400000123456789X,must be exactly 16 digits",
        "currencyCode,64,must be exactly 3 characters",
        "currencyCode,6430,must be exactly 3 characters",
        "mcc,123,must be exactly 4 digits",
        "mcc,12345,must be exactly 4 digits",
        "mcc,ABCD,must be exactly 4 digits",
        "terminalId,'',is required"
    })
    void gatewayRejectsInvalidField(String field, String value, String rule) {
        var payment = authorization("400000" + randomDigits(10), 100);
        payment.put(field, value);

        assertValidation(payment, "Field '" + field + "' " + rule);
    }

    @ParameterizedTest
    @ValueSource(strings = {"mti", "stan", "pan", "processingCode", "amount", "currencyCode",
        "transmissionDateTime", "terminalId", "merchantId", "mcc", "acquirerId"})
    void gatewayRejectsMissingRequiredField(String field) {
        var payment = authorization("400000" + randomDigits(10), 100);
        payment.remove(field);

        assertValidation(payment, "Field '" + field + "' is required");
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0})
    void gatewayRejectsNonpositiveAmount(int amount) {
        var payment = authorization("400000" + randomDigits(10), amount);

        assertValidation(payment, "Field 'amount' must be > 0");
    }

    @Test
    void gatewayRejectsMalformedJson() {
        assertValidation("{", "Request body must be valid JSON");
    }

    @Test
    void gatewayRejectsNullBody() {
        assertValidation("null", "Request body is required");
    }

    @Test
    void gatewayRejectedRequestLeavesNoSearchResult() {
        var payment = authorization(createCard(1000, 1000, 1000, "ACTIVE"), 0);
        assertValidation(payment, "Field 'amount' must be > 0");

        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> given().spec(GATEWAY).queryParam("pan", payment.get("pan"))
                        .when().get(SEARCH)
                        .then().statusCode(200)
                        .body("total", equalTo(0))
                        .body("transactions", hasSize(0)));
    }

    @Test
    void switchReturnsCardNotFoundForUnknownBin() {
        var payment = authorization("000001" + randomDigits(10), 100);

        given().spec(ApiSupport.SWITCH).body(payment)
                .when().post("/api/internal/route")
                .then().statusCode(200)
                .body("stan", equalTo(payment.get("stan")))
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("14"))
                .body("declineReason", equalTo("CARD_NOT_FOUND"));
    }

    private static void assertValidation(Object body, String message) {
        var request = given().spec(GATEWAY);
        if (body instanceof Map<?, ?> map) {
            request.body(map);
        } else {
            request.body((String) body);
        }

        request.when().post(TRANSACTIONS)
                .then().statusCode(400)
                .body("serviceName", equalTo("gateway"))
                .body("error", equalTo("VALIDATION_ERROR"))
                .body("message", equalTo(message));
    }
}
