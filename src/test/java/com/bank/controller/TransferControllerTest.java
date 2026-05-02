package com.bank.controller;

import com.bank.dto.TransferResponse;
import com.bank.entity.AccountStatus;
import com.bank.exception.AccountNotActiveException;
import com.bank.exception.LockAcquisitionException;
import com.bank.exception.TransactionLimitExceededException;
import com.bank.filter.TraceIdFilter;
import com.bank.security.JwtTokenProvider;
import com.bank.security.SecurityConfig;
import com.bank.service.TransferService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TransferController.class)
@Import({SecurityConfig.class, TransferControllerTest.TestConfig.class})
class TransferControllerTest {

    @TestConfiguration
    static class TestConfig {
        @Bean
        TraceIdFilter traceIdFilter() {
            return new TraceIdFilter();
        }
    }

    private static final Long USER_ID = 1L;
    private static final String FROM = "100-11111111";
    private static final String TO   = "100-22222222";
    private static final UsernamePasswordAuthenticationToken AUTH =
            new UsernamePasswordAuthenticationToken(USER_ID, null, List.of());

    @Autowired MockMvc mockMvc;
    @MockitoBean TransferService transferService;
    @MockitoBean JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("이체 - 인증 없으면 401")
    void transfer_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/transfers")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromAccountNumber":"100-11111111","toAccountNumber":"100-22222222","amount":10000}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("이체 성공 - 200 반환, 양측 잔액 포함")
    void transfer_success_returns200() throws Exception {
        given(transferService.transfer(any(), eq(USER_ID)))
                .willReturn(new TransferResponse(FROM, BigDecimal.valueOf(490_000),
                        TO, BigDecimal.valueOf(510_000), LocalDateTime.now()));

        mockMvc.perform(post("/transfers")
                        .with(csrf())
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromAccountNumber":"100-11111111","toAccountNumber":"100-22222222","amount":10000}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fromAccountNumber").value(FROM))
                .andExpect(jsonPath("$.data.fromBalanceAfter").value(490000))
                .andExpect(jsonPath("$.data.toAccountNumber").value(TO));
    }

    @Test
    @DisplayName("이체 - fromAccountNumber 누락 시 400")
    void transfer_missingFromAccount_returns400() throws Exception {
        mockMvc.perform(post("/transfers")
                        .with(csrf())
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"toAccountNumber":"100-22222222","amount":10000}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("이체 - 0원 이체 시 400 (Positive 제약)")
    void transfer_zeroAmount_returns400() throws Exception {
        mockMvc.perform(post("/transfers")
                        .with(csrf())
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromAccountNumber":"100-11111111","toAccountNumber":"100-22222222","amount":0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("이체 - 동결 계좌 이체 시도 시 409")
    void transfer_frozenAccount_returns409() throws Exception {
        given(transferService.transfer(any(), eq(USER_ID)))
                .willThrow(new AccountNotActiveException(AccountStatus.FROZEN));

        mockMvc.perform(post("/transfers")
                        .with(csrf())
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromAccountNumber":"100-11111111","toAccountNumber":"100-22222222","amount":10000}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("이체 - 분산 락 획득 실패 시 409")
    void transfer_lockFailed_returns409() throws Exception {
        given(transferService.transfer(any(), eq(USER_ID)))
                .willThrow(new LockAcquisitionException("락 획득 실패"));

        mockMvc.perform(post("/transfers")
                        .with(csrf())
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromAccountNumber":"100-11111111","toAccountNumber":"100-22222222","amount":10000}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("이체 - 거래 한도 초과 시 422")
    void transfer_limitExceeded_returns422() throws Exception {
        given(transferService.transfer(any(), eq(USER_ID)))
                .willThrow(new TransactionLimitExceededException(
                        TransactionLimitExceededException.LimitType.PER_TRANSACTION,
                        BigDecimal.valueOf(10_000_000),
                        BigDecimal.valueOf(15_000_000)));

        mockMvc.perform(post("/transfers")
                        .with(csrf())
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromAccountNumber":"100-11111111","toAccountNumber":"100-22222222","amount":15000000}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.success").value(false));
    }
}
