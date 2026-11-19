package com.processing.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static com.processing.api.ApiSupport.CARDS;
import static com.processing.api.ApiSupport.GATEWAY;
import static com.processing.api.ApiSupport.card;
import static com.processing.api.ApiSupport.createCard;
import static com.processing.api.ApiSupport.randomDigits;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;

class CardManagementTest {
    @Test
    void listFiltersByBinIssuerAndStatus() {
        createCard(1000, 1000, 1000, "BLOCKED");

        given().spec(GATEWAY).queryParam("status", "BLOCKED").queryParam("bin", "400000")
                .queryParam("issuerId", "ISS001").queryParam("limit", 1).queryParam("offset", 0)
                .when().get(CARDS)
                .then().statusCode(200).body("total", greaterThanOrEqualTo(1))
                .body("cards", hasSize(1)).body("cards[0].status", equalTo("BLOCKED"))
                .body("cards[0].bin", equalTo("400000")).body("cards[0].issuerId", equalTo("ISS001"));
    }

    @Test
    void createReadAndDeleteCard() {
        String pan = given().spec(GATEWAY).body(card(1000, 1000, 1000))
                .when().post(CARDS)
                .then().statusCode(201).body("pan", matchesPattern("400000[0-9]{10}"))
                .body("cardholderName", equalTo("TEST CARD")).extract().path("pan");

        given().spec(GATEWAY)
                .when().get(CARDS + "/{pan}", pan)
                .then().statusCode(200).body("pan", equalTo(pan)).body("currencyCode", equalTo("643"));

        given().spec(GATEWAY)
                .when().delete(CARDS + "/{pan}", pan)
                .then().statusCode(204).body(emptyString());
    }

    @ParameterizedTest
    @CsvSource({
        "bin,40000,BIN number must contain exactly 6 digits",
        "cardholderName,test name,uppercase letters",
        "currencyCode,64,3",
        "dailyLimit,-1,Value can not be negative",
        "monthlyLimit,-1,Value can not be negative"
    })
    void createCardRejectsInvalidFields(String field, String value, String rule) {
        var request = card(1000, 1000, 1000);
        request.put(field, value);

        given().spec(GATEWAY).body(request)
                .when().post(CARDS)
                .then().statusCode(400).body("serviceName", equalTo("card-management"))
                .body("message", containsString(rule));
    }

    @Test
    void reserveAndRollbackUpdateBalance() {
        String pan = createCard(1000, 1000, 1000, "ACTIVE");
        String rrn = randomDigits(12);

        given().spec(GATEWAY).body(Map.of("amount", 100, "rrn", rrn))
                .when().post(CARDS + "/{pan}/reserve", pan)
                .then().statusCode(200).body("pan", equalTo(pan))
                .body("availableBalance.toInteger()", equalTo(900));

        given().spec(GATEWAY).body(Map.of("amount", 100, "rrn", rrn))
                .when().post(CARDS + "/{pan}/reserve", pan)
                .then().statusCode(409).body("error", equalTo("ReservationAlreadyExistsException"));

        given().spec(GATEWAY).body(Map.of("pan", pan, "amount", 100, "rrn", rrn))
                .when().post(CARDS + "/{pan}/rollback", pan)
                .then().statusCode(200).body("pan", equalTo(pan))
                .body("availableBalance.toInteger()", equalTo(1000));
    }

    @Test
    void generateCreatesRequestedNumberOfCards() {
        given().spec(GATEWAY).body(Map.of("count", 1, "bins", List.of("400000")))
                .when().post(CARDS + "/generate")
                .then().statusCode(201).body("generated", equalTo(1)).body("cards", hasSize(1))
                .body("cards[0].pan", matchesPattern("400000[0-9]{10}"));
    }

    @Test
    void bulkUpdateChangesOnlySelectedCard() {
        String selected = createCard(1000, 1000, 1000, "ACTIVE");
        String other = createCard(1000, 1000, 1000, "ACTIVE");

        given().spec(GATEWAY).body(Map.of("pans", List.of(selected), "status", "BLOCKED"))
                .when().post(CARDS + "/bulk-update")
                .then().statusCode(200).body("updated", equalTo(1));

        given().spec(GATEWAY)
                .when().get(CARDS + "/{pan}", selected)
                .then().statusCode(200).body("status", equalTo("BLOCKED"));

        given().spec(GATEWAY)
                .when().get(CARDS + "/{pan}", other)
                .then().statusCode(200).body("status", equalTo("ACTIVE"));
    }

    @ParameterizedTest
    @CsvSource({"limit,0", "offset,-1", "bin,12345", "issuerId,invalid"})
    void listRejectsInvalidQueryParameters(String parameter, String value) {
        given().spec(GATEWAY).queryParam(parameter, value)
                .when().get(CARDS)
                .then().statusCode(400).body("message", containsString(parameter))
                .body("error", equalTo("ConstraintViolationException"));
    }
}
