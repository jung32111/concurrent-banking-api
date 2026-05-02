package com.bank.controller;

import com.bank.dto.TransactionResponse;
import com.bank.entity.TransactionType;
import com.bank.exception.InsufficientBalanceException;
import com.bank.filter.TraceIdFilter;
import com.bank.security.JwtTokenProvider;
import com.bank.security.SecurityConfig;
import com.bank.service.TransactionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TransactionController.class)
@Import({SecurityConfig.class, TransactionControllerTest.TestConfig.class})
class TransactionControllerTest {

    @TestConfiguration
    static class TestConfig {
        @Bean
        TraceIdFilter traceIdFilter() {
            return new TraceIdFilter();
        }
    }

    private static final Long USER_ID = 1L;
    private static final String ACCOUNT_NO = "100-12345678";
    private static final UsernamePasswordAuthenticationToken AUTH =
            new UsernamePasswordAuthenticationToken(USER_ID, null, List.of());

    @Autowired MockMvc mockMvc;
    @MockitoBean TransactionService transactionService;
    @MockitoBean JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("입출금 - 인증 없으면 401")
    void createTransaction_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/transactions")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountNumber":"100-12345678","amount":10000,"type":"DEPOSIT"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("입금 성공 - 201 반환")
    void createTransaction_deposit_returns201() throws Exception {
        given(transactionService.createTransaction(any(), eq(USER_ID)))
                .willReturn(stubTransaction(ACCOUNT_NO, TransactionType.DEPOSIT, BigDecimal.valueOf(10_000)));

        mockMvc.perform(post("/transactions")
                        .with(csrf())
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountNumber":"100-12345678","amount":10000,"type":"DEPOSIT"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.type").value("DEPOSIT"));
    }

    @Test
    @DisplayName("출금 - 잔액 부족 시 400")
    void createTransaction_insufficientBalance_returns400() throws Exception {
        given(transactionService.createTransaction(any(), eq(USER_ID)))
                .willThrow(new InsufficientBalanceException());

        mockMvc.perform(post("/transactions")
                        .with(csrf())
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountNumber":"100-12345678","amount":99999999,"type":"WITHDRAW"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("입출금 - accountNumber 누락 시 400")
    void createTransaction_missingAccountNumber_returns400() throws Exception {
        mockMvc.perform(post("/transactions")
                        .with(csrf())
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":10000,"type":"DEPOSIT"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("입출금 - 음수 금액 시 400")
    void createTransaction_negativeAmount_returns400() throws Exception {
        mockMvc.perform(post("/transactions")
                        .with(csrf())
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountNumber":"100-12345678","amount":-1000,"type":"DEPOSIT"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("거래 내역 조회 - 페이징 포함 200 반환")
    void getTransactions_withAuth_returns200() throws Exception {
        given(transactionService.getTransactions(eq(ACCOUNT_NO), eq(USER_ID), any(PageRequest.class)))
                .willReturn(new PageImpl<>(
                        List.of(stubTransaction(ACCOUNT_NO, TransactionType.DEPOSIT, BigDecimal.valueOf(10_000))),
                        PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/transactions/{no}", ACCOUNT_NO)
                        .with(authentication(AUTH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].type").value("DEPOSIT"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    private TransactionResponse stubTransaction(String accountNumber, TransactionType type, BigDecimal amount) {
        return TransactionResponse.builder()
                .id(1L)
                .accountNumber(accountNumber)
                .amount(amount)
                .type(type)
                .balanceAfterTransaction(BigDecimal.valueOf(1_010_000))
                .createdAt(LocalDateTime.now())
                .build();
    }
}
