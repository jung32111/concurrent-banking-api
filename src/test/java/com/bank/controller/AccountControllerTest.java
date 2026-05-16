package com.bank.controller;

import com.bank.dto.AccountResponse;
import com.bank.entity.AccountStatus;
import com.bank.exception.AccountNotFoundException;
import com.bank.exception.UnauthorizedAccessException;
import com.bank.filter.TraceIdFilter;
import com.bank.security.JwtTokenProvider;
import com.bank.security.SecurityConfig;
import com.bank.security.TokenBlacklistService;
import com.bank.service.AccountService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AccountController.class)
@Import({SecurityConfig.class, AccountControllerTest.TestConfig.class})
class AccountControllerTest {

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
            new UsernamePasswordAuthenticationToken(USER_ID, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    private static final UsernamePasswordAuthenticationToken ADMIN_AUTH =
            new UsernamePasswordAuthenticationToken(USER_ID, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

    @Autowired MockMvc mockMvc;
    @MockitoBean AccountService accountService;
    @MockitoBean JwtTokenProvider jwtTokenProvider;
    @MockitoBean TokenBlacklistService tokenBlacklistService;

    @Test
    @DisplayName("계좌 목록 조회 - 인증 없으면 401")
    void getAccounts_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/accounts"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("계좌 목록 조회 - 인증 성공 시 200")
    void getAccounts_withAuth_returns200() throws Exception {
        given(accountService.getMyAccounts(USER_ID))
                .willReturn(List.of(stubAccount(ACCOUNT_NO, AccountStatus.ACTIVE)));

        mockMvc.perform(get("/accounts")
                        .with(authentication(AUTH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].accountNumber").value(ACCOUNT_NO));
    }

    @Test
    @DisplayName("계좌 개설 - 유효한 요청 시 201")
    void createAccount_success_returns201() throws Exception {
        given(accountService.createAccount(any(), eq(USER_ID)))
                .willReturn(stubAccount(ACCOUNT_NO, AccountStatus.ACTIVE));

        mockMvc.perform(post("/accounts")
                        .with(csrf())
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ownerName":"홍길동"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.accountNumber").value(ACCOUNT_NO));
    }

    @Test
    @DisplayName("계좌 개설 - ownerName 누락 시 400")
    void createAccount_blankOwnerName_returns400() throws Exception {
        mockMvc.perform(post("/accounts")
                        .with(csrf())
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ownerName":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("계좌 조회 - 타인 계좌 접근 시 403")
    void getAccount_unauthorizedAccess_returns403() throws Exception {
        given(accountService.getAccount(eq(ACCOUNT_NO), eq(USER_ID)))
                .willThrow(new UnauthorizedAccessException());

        mockMvc.perform(get("/accounts/{no}", ACCOUNT_NO)
                        .with(authentication(AUTH)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("계좌 조회 - 존재하지 않는 계좌 시 404")
    void getAccount_notFound_returns404() throws Exception {
        given(accountService.getAccount(anyString(), anyLong()))
                .willThrow(new AccountNotFoundException());

        mockMvc.perform(get("/accounts/{no}", "000-00000000")
                        .with(authentication(AUTH)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("잔액 조회 - 200 반환")
    void getBalance_withAuth_returns200() throws Exception {
        given(accountService.getBalance(eq(ACCOUNT_NO), eq(USER_ID)))
                .willReturn(BigDecimal.valueOf(500_000));

        mockMvc.perform(get("/accounts/{no}/balance", ACCOUNT_NO)
                        .with(authentication(AUTH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(500000));
    }

    @Test
    @DisplayName("계좌 동결 - ADMIN 권한이면 200 및 FROZEN 상태 확인")
    void freeze_withAdminAuth_returns200() throws Exception {
        given(accountService.freeze(eq(ACCOUNT_NO), eq(USER_ID)))
                .willReturn(stubAccount(ACCOUNT_NO, AccountStatus.FROZEN));

        mockMvc.perform(post("/accounts/{no}/freeze", ACCOUNT_NO)
                        .with(csrf())
                        .with(authentication(ADMIN_AUTH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FROZEN"));
    }

    @Test
    @DisplayName("계좌 동결 - USER 권한이면 403")
    void freeze_withUserAuth_returns403() throws Exception {
        mockMvc.perform(post("/accounts/{no}/freeze", ACCOUNT_NO)
                        .with(csrf())
                        .with(authentication(AUTH)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("계좌 동결 해제 - ADMIN 권한이면 200 및 ACTIVE 상태 확인")
    void unfreeze_withAdminAuth_returns200() throws Exception {
        given(accountService.unfreeze(eq(ACCOUNT_NO), eq(USER_ID)))
                .willReturn(stubAccount(ACCOUNT_NO, AccountStatus.ACTIVE));

        mockMvc.perform(post("/accounts/{no}/unfreeze", ACCOUNT_NO)
                        .with(csrf())
                        .with(authentication(ADMIN_AUTH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("계좌 동결 해제 - USER 권한이면 403")
    void unfreeze_withUserAuth_returns403() throws Exception {
        mockMvc.perform(post("/accounts/{no}/unfreeze", ACCOUNT_NO)
                        .with(csrf())
                        .with(authentication(AUTH)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("휴면 계좌 활성화 - 200 반환 및 ACTIVE 상태 확인")
    void activate_withAuth_returns200() throws Exception {
        given(accountService.activate(eq(ACCOUNT_NO), eq(USER_ID)))
                .willReturn(stubAccount(ACCOUNT_NO, AccountStatus.ACTIVE));

        mockMvc.perform(post("/accounts/{no}/activate", ACCOUNT_NO)
                        .with(csrf())
                        .with(authentication(AUTH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    private AccountResponse stubAccount(String accountNumber, AccountStatus status) {
        return AccountResponse.builder()
                .id(1L)
                .accountNumber(accountNumber)
                .ownerName("홍길동")
                .balance(BigDecimal.valueOf(1_000_000))
                .status(status)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
