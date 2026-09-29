package com.duraledger.ledger.account;

import com.duraledger.ledger.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountApiTests {

    @Value("${local.server.port}") int port;
    @Autowired JsonMapper json;
    @Autowired JdbcClient jdbc;

    RestClient http;

    @BeforeEach
    void setUp() {
        http = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { }) // assert on 4xx, don't throw
                .build();
    }

    @Test
    void a_new_account_can_be_fetched_and_starts_at_zero() {
        var userId = UUID.randomUUID();
        var created = create(userId, "HKD");

        assertThat(created.status()).isEqualTo(201);
        var id = created.body().get("id").asString();
        var fetched = get("/accounts/" + id);
        assertThat(fetched.status()).isEqualTo(200);
        assertThat(fetched.body().get("userId").asString()).isEqualTo(userId.toString());
        assertThat(fetched.body().get("currency").asString()).isEqualTo("HKD");
        assertThat(get("/accounts/" + id + "/balance").body().get("balanceMinor").asLong()).isZero();
    }

    @Test
    void unsupported_currency_is_a_bad_request() {
        assertThat(create(UUID.randomUUID(), "JPY").status()).isEqualTo(400);
    }

    @Test
    void unknown_accounts_are_not_found() {
        assertThat(get("/accounts/" + UUID.randomUUID()).status()).isEqualTo(404);
    }

    @Test
    void system_accounts_are_not_reachable_through_the_public_api() {
        var clearing = jdbc.sql("SELECT id FROM accounts WHERE kind = 'EXTERNAL_CLEARING' AND currency = 'USD'")
                .query(UUID.class).single();

        assertThat(get("/accounts/" + clearing).status()).isEqualTo(404);
        assertThat(get("/accounts/" + clearing + "/balance").status()).isEqualTo(404);
    }

    // --- helpers -------------------------------------------------------------

    record Response(int status, JsonNode body) {}

    private Response create(UUID userId, String currency) {
        return call(http.post().uri("/accounts").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("userId", userId, "currency", currency)));
    }

    private Response get(String path) {
        return call(http.get().uri(path));
    }

    private Response call(RestClient.RequestHeadersSpec<?> request) {
        return request.exchange((req, res) -> {
            String body = new String(res.getBody().readAllBytes());
            return new Response(res.getStatusCode().value(), body.isEmpty() ? null : json.readTree(body));
        });
    }
}
