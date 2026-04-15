package com.bank.accountservice.idempotency;

import com.bank.accountservice.repository.IdempotencyKeyRepository;
import com.bank.accountservice.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(IdempotencyFilterTest.TestApiConfig.class)
class IdempotencyFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private AtomicInteger depositCallCounter;

    @BeforeEach
    void setUp() {
        idempotencyKeyRepository.deleteAll();
        depositCallCounter.set(0);
    }

    @Test
    void sameKeyTwice_secondResponseContainsReplayHeader() throws Exception {
        performDeposit("idem-replay");

        mockMvc.perform(post("/api/accounts/deposit")
                        .header("Authorization", bearerToken())
                        .header("Idempotency-Key", "idem-replay"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Idempotency-Replayed", "true"));
    }

    @Test
    void sameKeyTwice_responseBodiesAreIdentical() throws Exception {
        MvcResult first = performDeposit("idem-body");
        MvcResult second = performDeposit("idem-body");

        String firstBody = first.getResponse().getContentAsString();
        String secondBody = second.getResponse().getContentAsString();

        assertThat(secondBody).isEqualTo(firstBody);
    }

    @Test
    void sameKeyTwice_onlyOneRowIsStored() throws Exception {
        performDeposit("idem-db-count");
        performDeposit("idem-db-count");

        assertThat(idempotencyKeyRepository.count()).isEqualTo(1L);
    }

    @Test
    void missingIdempotencyKeyHeader_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/accounts/deposit")
                        .header("Authorization", bearerToken()))
                .andExpect(status().isBadRequest());
    }

    private MvcResult performDeposit(String idempotencyKey) throws Exception {
        return mockMvc.perform(post("/api/accounts/deposit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", bearerToken())
                        .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isOk())
                .andReturn();
    }

    private String bearerToken() {
        return "Bearer " + jwtTokenProvider.generateToken(1L, "idempotency@test.com");
    }

    @TestConfiguration
    static class TestApiConfig {

        @Bean
        AtomicInteger depositCallCounter() {
            return new AtomicInteger(0);
        }

        @Bean
        TestApiController testApiController(AtomicInteger depositCallCounter) {
            return new TestApiController(depositCallCounter);
        }
    }

    @RestController
    @RequestMapping("/api/accounts")
    static class TestApiController {

        private final AtomicInteger depositCallCounter;

        TestApiController(AtomicInteger depositCallCounter) {
            this.depositCallCounter = depositCallCounter;
        }

        @PostMapping("/deposit")
        public String deposit() {
            int currentCount = depositCallCounter.incrementAndGet();
            return "{\"callCount\":" + currentCount + "}";
        }

        @PostMapping("/withdraw")
        public String withdraw() {
            return "{\"status\":\"ok\"}";
        }

        @PostMapping("/transfer")
        public String transfer() {
            return "{\"status\":\"ok\"}";
        }
    }
}

