package com.processing.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.processing.api.ApiSupport.GATEWAY;
import static com.processing.api.ApiSupport.LOGGER;
import static com.processing.api.ApiSupport.SEARCH;
import static com.processing.api.ApiSupport.log;
import static com.processing.api.ApiSupport.randomDigits;
import static com.processing.api.ApiSupport.store;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;

class TransactionLoggerTest {
    private static final String LOG = "/api/internal/log";

    // InternalTransactionController specifies 201 for a new ID, 200 for a replay and 409 on conflict.
    @Test
    void sameIdAndPayloadAreIdempotentAndDifferentPayloadConflicts() {
        String pan = "400000" + randomDigits(10);
        var transaction = log(pan);
        Object id = transaction.get("id");
        store(transaction);

        given().spec(LOGGER).body(transaction)
                .when().post(LOG)
                .then().statusCode(200)
                .body("id", equalTo(id))
                .body("stan", equalTo(transaction.get("stan")))
                .body("status", equalTo("APPROVED"));

        transaction.put("amount", 101);

        given().spec(LOGGER).body(transaction)
                .when().post(LOG)
                .then().statusCode(409)
                .body("serviceName", equalTo("transaction-logger"))
                .body("error", equalTo("Transaction conflict"))
                .body("message", equalTo("Transaction with id " + id + " already exists with different data"));

        given().spec(GATEWAY).queryParam("pan", pan)
                .when().get(SEARCH)
                .then().statusCode(200)
                .body("total", equalTo(1))
                .body("transactions", hasSize(1))
                .body("transactions[0].id", equalTo(id))
                .body("transactions[0].amount.toInteger()", equalTo(100));
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "mti", "stan", "pan", "processingCode", "amount", "currencyCode",
        "terminalId", "merchantId", "mcc", "acquirerId", "status", "transmissionDateTime", "createdAt"})
    void loggerNamesMissingRequiredField(String field) {
        var transaction = log("400000" + randomDigits(10));
        transaction.remove(field);

        assertLoggerValidation(transaction, field);
    }

    @ParameterizedTest
    @CsvSource({
        "mti,01000", "stan,12345", "stan,1234567", "stan,ABCDEF", "rrn,1234567890123",
        "pan,400000123456789", "pan,40000012345678901", "pan,400000123456789X",
        "processingCode,00000", "processingCode,0000000", "processingCode,XXXXXX",
        "currencyCode,64", "currencyCode,6430", "currencyCode,ABC",
        "terminalId,123456789", "terminalType,12345678901", "merchantId,1234567890123456",
        "mcc,123", "mcc,12345", "mcc,ABCD", "acquirerId,12345678901", "issuerId,12345678901",
        "authCode,ABCDEFG"
    })
    void loggerRejectsInvalidLengthsAndFormats(String field, String value) {
        var transaction = log("400000" + randomDigits(10));
        transaction.put(field, value);

        assertLoggerValidation(transaction, field);
    }

    @ParameterizedTest
    @CsvSource({"amount,0", "amount,-1", "acquiringFee,-1", "processingTimeMs,-1"})
    void loggerRejectsOutOfRangeNumbers(String field, int value) {
        var transaction = log("400000" + randomDigits(10));
        transaction.put(field, value);

        assertLoggerValidation(transaction, field);
    }

    private static void assertLoggerValidation(Map<String, Object> transaction, String field) {
        given().spec(LOGGER).body(transaction)
                .when().post(LOG)
                .then().statusCode(400)
                .body("serviceName", equalTo("transaction-logger"))
                .body("error", equalTo("Validation error"))
                .body("message", startsWith(field + ": "));
    }

    @ParameterizedTest
    @CsvSource({"limit,0", "limit,501", "offset,-1", "limit,abc", "status,UNKNOWN", "dateFrom,2026-02-30"})
    void searchReportsInvalidQueryParameter(String parameter, String value) {
        given().spec(GATEWAY).queryParam(parameter, value)
                .when().get(SEARCH)
                .then().statusCode(400)
                .body("serviceName", equalTo("transaction-logger"))
                .body("message", containsString(parameter));
    }

    @Test
    void searchRejectsReversedDateRange() {
        given().spec(GATEWAY).queryParam("dateFrom", "2026-02-02").queryParam("dateTo", "2026-02-01")
                .when().get(SEARCH)
                .then().statusCode(400)
                .body("serviceName", equalTo("transaction-logger"))
                .body("error", equalTo("Validation error"));
    }

    @Test
    void searchAppliesAllFiltersToDeclinedTransactionAndHonorsPageOffset() {
        String pan = "400000" + randomDigits(10);
        var transaction = log(pan);
        transaction.put("status", "DECLINED");
        transaction.put("declineReason", "Insufficient funds");
        transaction.remove("authCode");
        store(transaction);
        var other = log(pan);
        store(other);
        String day = transaction.get("transmissionDateTime").toString().substring(0, 10);
        var filters = Map.of("pan", pan, "status", "DECLINED", "dateFrom", day, "dateTo", day,
                "merchantId", transaction.get("merchantId"), "issuerId", "ISS001",
                "mcc", "5411", "declineReason", "funds");

        given().spec(GATEWAY).queryParams(filters).queryParam("limit", 1)
                .when().get(SEARCH)
                .then().statusCode(200).body("total", equalTo(1))
                .body("transactions", hasSize(1)).body("transactions[0].id", equalTo(transaction.get("id")));

        given().spec(GATEWAY).queryParams(filters).queryParam("limit", 500).queryParam("offset", 1)
                .when().get(SEARCH)
                .then().statusCode(200).body("total", equalTo(1)).body("transactions", hasSize(0));
    }

    // Distinct nonzero binary masks are pairwise independent over all 16 four-bit rows.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15})
    void searchCombinesOptionalParametersPairwise(int row) {
        String pan = "400000" + randomDigits(10);
        var transaction = log(pan);
        transaction.put("status", "DECLINED");
        transaction.put("declineReason", "Insufficient funds");
        transaction.remove("authCode");
        store(transaction);
        String day = transaction.get("transmissionDateTime").toString().substring(0, 10);
        var names = new String[] {
            "status", "dateFrom", "dateTo", "merchantId", "issuerId", "mcc", "declineReason", "limit", "offset"
        };
        var values = new Object[] {
            "DECLINED", day, day, transaction.get("merchantId"), "ISS001", "5411", "funds", 1, 1
        };
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("pan", pan);
        for (int index = 0; index < names.length; index++) {
            if (Integer.bitCount(row & (index + 1)) % 2 == 1) {
                filters.put(names[index], values[index]);
            }
        }

        int expectedSize = filters.containsKey("offset") ? 0 : 1;

        given().spec(GATEWAY).queryParams(filters)
                .when().get(SEARCH)
                .then().statusCode(200).body("total", equalTo(1))
                .body("transactions", hasSize(expectedSize));
    }

    @ParameterizedTest
    @CsvSource({
        "status,APPROVED", "dateFrom,2025-04-07", "dateTo,2025-04-05",
        "merchantId,MERCH9999999999", "issuerId,ISS999", "mcc,9999", "declineReason,different"
    })
    void eachSearchFilterCanExcludeTheFixture(String parameter, String value) {
        String pan = "400000" + randomDigits(10);
        var transaction = log(pan);
        transaction.put("transmissionDateTime", "2025-04-06T00:00:00Z");
        transaction.put("status", "DECLINED");
        transaction.put("declineReason", "Insufficient funds");
        transaction.remove("authCode");
        store(transaction);

        given().spec(GATEWAY).queryParam("pan", pan).queryParam(parameter, value)
                .when().get(SEARCH)
                .then().statusCode(200).body("total", equalTo(0)).body("transactions", hasSize(0));
    }

    @Test
    void exportIncludesMatchingRowEvenWithPositiveOffset() {
        String pan = "400000" + randomDigits(10);
        var transaction = log(pan);
        store(transaction);

        given().spec(GATEWAY).queryParam("pan", pan).queryParam("offset", 1)
                .when().get("/api/transactions/export")
                .then().statusCode(200).contentType("text/csv")
                .header("Content-Disposition", equalTo("attachment; filename=\"transactions.csv\""))
                .body(containsString(transaction.get("id").toString()));
    }

    @Test
    void searchDateFilterExcludesFollowingDay() {
        String pan = "400000" + randomDigits(10);
        store(log(pan));
        String tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1).toString();

        given().spec(GATEWAY).queryParam("pan", pan).queryParam("dateFrom", tomorrow)
                .when().get(SEARCH)
                .then().statusCode(200).body("total", equalTo(0)).body("transactions", hasSize(0));
    }

}
