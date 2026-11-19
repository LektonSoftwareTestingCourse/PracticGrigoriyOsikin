package com.processing.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.processing.api.ApiSupport.CARDS;
import static com.processing.api.ApiSupport.GATEWAY;
import static com.processing.api.ApiSupport.SEARCH;
import static com.processing.api.ApiSupport.SWITCH;
import static com.processing.api.ApiSupport.authorization;
import static com.processing.api.ApiSupport.createCard;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

class RollbackOnPublishFailureIT {
    // RouteService compensates an approved debit if LoggerClient cannot publish.
    @Test
    void rollbackRestoresFundsAndLeavesNoTransactionInSearch() {
        String pan = createCard(1000, 1000, 1000, "ACTIVE");
        var payment = authorization(pan, 100);

        given().spec(SWITCH).body(payment)
                .when().post("/api/internal/route")
                .then().statusCode(200)
                .body("stan", equalTo(payment.get("stan")))
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("96"))
                .body("declineReason", equalTo("System error"))
                .body("rrn", nullValue());

        given().spec(GATEWAY)
                .when().get(CARDS + "/{pan}", pan)
                .then().statusCode(200).body("availableBalance.toInteger()", equalTo(1000));

        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> given().spec(GATEWAY).queryParam("pan", pan)
                        .when().get(SEARCH)
                        .then().statusCode(200)
                        .body("total", equalTo(0))
                        .body("transactions", hasSize(0)));
    }
}
