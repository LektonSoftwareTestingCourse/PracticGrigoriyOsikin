package com.processing.api;

import org.junit.jupiter.api.Test;

import static com.processing.api.ApiSupport.GATEWAY;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

class GatewayRateLimitIT {
    // TransactionRateLimitFilter consumes the token before request validation.
    @Test
    void exhaustedBucketReturnsRetryAfterAndStructuredError() {
        String path = "/api/transactions";

        given().spec(GATEWAY).body("{}")
                .when().post(path)
                .then().statusCode(400).body("error", equalTo("VALIDATION_ERROR"));

        given().spec(GATEWAY).body("{}")
                .when().post(path)
                .then().statusCode(429)
                .header("Retry-After", equalTo("1"))
                .body("error", equalTo("RATE_LIMIT_EXCEEDED"))
                .body("message", equalTo("Too many requests. Try again later."))
                .body("retryAfterMs", equalTo(1000));
    }
}
