package com.processing.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static com.processing.api.ApiSupport.GATEWAY;
import static com.processing.api.ApiSupport.MERCHANT;
import static com.processing.api.ApiSupport.createCard;
import static com.processing.api.ApiSupport.log;
import static com.processing.api.ApiSupport.randomDigits;
import static com.processing.api.ApiSupport.store;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.oneOf;

class GatewayRoutesTest {
    @Test
    void gatewayReportsDownstreamHealth() {
        given().spec(GATEWAY)
                .when().get("/health")
                .then().statusCode(200)
                .body("status", equalTo("ok"))
                .body("service", equalTo("gateway"))
                .body("services", notNullValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"switch", "logger", "terminal", "merchant", "cards"})
    void gatewayServesDownstreamOpenApi(String service) {
        given().spec(GATEWAY)
                .when().get("/{service}-docs", service)
                .then().statusCode(200).contentType("application/json")
                .body("openapi", matchesPattern("3\\.[01]\\.\\d+"))
                .body("paths", notNullValue());
    }

    @Test
    void dashboardIncludesStoredTransaction() {
        var transaction = log("400000" + randomDigits(10));
        store(transaction);

        given().spec(GATEWAY).queryParam("limit", 1)
                .when().get("/api/dashboard/recent")
                .then().statusCode(200).body("", hasSize(1))
                .body("[0].id", equalTo(transaction.get("id")));

        given().spec(GATEWAY)
                .when().get("/api/dashboard/stats")
                .then().statusCode(200).body("totalTransactions", greaterThanOrEqualTo(1))
                .body("approvedCount", greaterThanOrEqualTo(1));

        given().spec(GATEWAY).queryParam("granularity", "day")
                .when().get("/api/dashboard/charts")
                .then().statusCode(200).body("[0].timestamp", notNullValue())
                .body("[0].total", greaterThanOrEqualTo(1));
    }

    @Test
    void documentedTerminalStartFallsThroughToGenericErrorHandler() {
        String path = "/api/simulator/terminal/start";

        given().spec(GATEWAY).body(Map.of("count", 1, "scenario", "normal", "tps", 1))
                .when().post(path)
                .then().statusCode(500).body("serviceName", equalTo("terminal-simulator"))
                .body("message", containsString("api/simulator/terminal/start"));
    }

    @Test
    void documentedTerminalStatusFallsThroughToGenericErrorHandler() {
        given().spec(GATEWAY)
                .when().get("/api/simulator/terminal/status")
                .then().statusCode(500).body("serviceName", equalTo("terminal-simulator"))
                .body("message", containsString("api/simulator/terminal/status"));
    }

    @Test
    void gatewayHasNoWebSocketRoute() {
        String path = "/ws/transactions";

        given().spec(GATEWAY)
                .when().get(path)
                .then().statusCode(404).body("path", equalTo(path));
    }

    @Test
    void oneShotTerminalSimulationReportsOneAuthorization() {
        createCard(1000000, 1000000, 1000000, "ACTIVE");

        given().spec(GATEWAY).body(Map.of("count", 1, "scenario", "normal", "tps", 1))
                .when().post("/api/simulator/terminal/run")
                .then().statusCode(200).body("totalSubmitted", equalTo(1))
                .body("transactions", hasSize(1))
                .body("transactions[0].status", oneOf("APPROVED", "DECLINED"));
    }

    @Test
    void continuousTerminalSimulationCanStartAndStop() {
        createCard(1000000, 1000000, 1000000, "ACTIVE");

        try {
            given().spec(GATEWAY).body(Map.of("tps", 1, "transactionType", "NORMAL"))
                    .when().post("/api/simulator/terminal/start-continuous")
                    .then().statusCode(200).body(emptyString());
        } finally {
            given().spec(GATEWAY)
                    .when().post("/api/simulator/terminal/stop")
                    .then().statusCode(200).body(emptyString());
        }
    }

    @Test
    void merchantSimulationReportsOneAuthorization() {
        createCard(1000000, 1000000, 1000000, "ACTIVE");

        given().spec(GATEWAY).body(Map.of("count", 1, "scenario", "grocery", "tps", 10))
                .when().post("/api/simulator/merchant/run")
                .then().statusCode(200).body("totalSubmitted", equalTo(1))
                .body("transactions", hasSize(1))
                .body("transactions[0].status", oneOf("APPROVED", "DECLINED"));
    }

    @Test
    void merchantCatalogExistsDirectlyButHasNoGatewayRoute() {
        String path = "/api/simulator/merchants";

        given().spec(MERCHANT)
                .when().get(path)
                .then().statusCode(200).body("[0].id", notNullValue()).body("[0].mcc", notNullValue());

        given().spec(GATEWAY)
                .when().get(path)
                .then().statusCode(404).body("path", equalTo(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {"limit=0", "limit=501"})
    void dashboardRejectsRecentLimitOutsideBounds(String query) {
        given().spec(GATEWAY)
                .when().get("/api/dashboard/recent?" + query)
                .then().statusCode(400).body("serviceName", equalTo("transaction-logger"))
                .body("message", containsString("limit"));
    }

    @Test
    void merchantRejectsNonpositiveTransactionCount() {
        given().spec(GATEWAY).body(Map.of("count", 0, "scenario", "grocery", "tps", 10))
                .when().post("/api/simulator/merchant/run")
                .then().statusCode(400).body("serviceName", equalTo("Merchant acquirer simulator"))
                .body("message", containsString("count"));
    }

    @Test
    void gatewayForwardsMerchantFeeNotFound() {
        var request = Map.of("transmissionDateTime", ApiSupport.now(),
                "pan", "400000" + randomDigits(10), "stan", randomDigits(6),
                "terminalId", "T" + randomDigits(7), "amount", 100);

        given().spec(GATEWAY).body(request)
                .when().post("/api/simulator/merchant/fee")
                .then().statusCode(404).body("error", equalTo("Resource not found"))
                .body("serviceName", equalTo("Merchant acquirer simulator"))
                .body("message", equalTo("Acquirer fee not found for stan = " + request.get("stan")));
    }
}
