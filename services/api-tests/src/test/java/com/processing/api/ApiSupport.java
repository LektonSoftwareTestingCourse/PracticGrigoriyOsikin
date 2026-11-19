package com.processing.api;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;

final class ApiSupport {
    static final String CARDS = "/api/cards";
    static final String SEARCH = "/api/transactions/search";
    static final RequestSpecification GATEWAY = endpoint("GATEWAY_PORT", "8080");
    static final RequestSpecification SWITCH = endpoint("SWITCH_PORT", "8082");
    static final RequestSpecification AUTH = endpoint("AUTH_PORT", "8083");
    static final RequestSpecification LOGGER = endpoint("LOGGER_PORT", "8088");
    static final RequestSpecification MERCHANT = endpoint("MERCHANT_PORT", "8084");
    static final RequestSpecification CARD_SERVICE = endpoint("CARD_MGMT_PORT", "8081");

    private ApiSupport() {
    }

    private static RequestSpecification endpoint(String variable, String fallback) {
        String host = System.getenv().getOrDefault("API_TEST_HOST", "localhost");
        String port = System.getenv().getOrDefault(variable, fallback);
        var http = HttpClientConfig.httpClientConfig()
                .setParam("http.connection.timeout", 3000)
                .setParam("http.socket.timeout", 15000);
        return new RequestSpecBuilder().setConfig(RestAssuredConfig.config().httpClient(http))
                .setBaseUri("http://" + host + ":" + port)
                .setAccept(ContentType.ANY).setContentType(ContentType.JSON).build();
    }

    static Map<String, Object> card(int balance, int daily, int monthly) {
        return new LinkedHashMap<>(Map.of("bin", "400000", "cardholderName", "TEST CARD",
                "currencyCode", "643", "initialBalance", balance, "dailyLimit", daily,
                "monthlyLimit", monthly));
    }

    static String createCard(int balance, int daily, int monthly, String status) {
        String pan = given().spec(GATEWAY).body(card(balance, daily, monthly))
                .when().post(CARDS)
                .then().statusCode(201).body("pan", matchesPattern("400000[0-9]{10}"))
                .extract().path("pan");

        given().spec(GATEWAY).body(Map.of("status", status))
                .when().patch(CARDS + "/{pan}", pan)
                .then().statusCode(200).body("status", equalTo(status))
                .body("availableBalance.toInteger()", equalTo(balance));
        return pan;
    }

    static Map<String, Object> authorization(String pan, int amount) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mti", "0100");
        data.put("stan", randomDigits(6));
        data.put("pan", pan);
        data.put("processingCode", "000000");
        data.put("amount", amount);
        data.put("currencyCode", "643");
        data.put("transmissionDateTime", now());
        data.put("terminalId", "T" + randomDigits(7));
        data.put("terminalType", "POS");
        data.put("merchantId", "MERCH" + randomDigits(10));
        data.put("mcc", "5411");
        data.put("acquirerId", "ACQ001");
        data.put("issuerId", "ISS001");
        return data;
    }

    static Map<String, Object> log(String pan) {
        Map<String, Object> data = authorization(pan, 100);
        data.put("id", UUID.randomUUID().toString());
        data.put("rrn", randomDigits(12));
        data.put("status", "APPROVED");
        data.put("authCode", "TEST01");
        data.put("createdAt", now());
        return data;
    }

    static void store(Map<String, Object> transaction) {
        given().spec(LOGGER).body(transaction)
                .when().post("/api/internal/log")
                .then().statusCode(201)
                .body("id", equalTo(transaction.get("id")))
                .body("status", equalTo("stored"));
    }

    static String randomDigits(int length) {
        long bound = (long) Math.pow(10, length);
        return String.format(Locale.ROOT, "%0" + length + "d", ThreadLocalRandom.current().nextLong(bound));
    }

    static String now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
    }
}
