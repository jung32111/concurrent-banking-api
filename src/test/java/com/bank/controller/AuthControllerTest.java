package com.bank.controller;

import com.bank.dto.TokenResponse;
import com.bank.exception.DuplicateEmailException;
import com.bank.exception.InvalidCredentialsException;
import com.bank.exception.InvalidTokenException;
import com.bank.filter.TraceIdFilter;
import com.bank.security.JwtTokenProvider;
import com.bank.security.SecurityConfig;
import com.bank.security.TokenBlacklistService;
import com.bank.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, AuthControllerTest.TestConfig.class})
class AuthControllerTest {

    @TestConfiguration
    static class TestConfig {
        @Bean
        TraceIdFilter traceIdFilter() {
            return new TraceIdFilter();
        }
    }

    private static final UsernamePasswordAuthenticationToken AUTH =
            new UsernamePasswordAuthenticationToken(1L, null, List.of());

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean AuthService authService;
    @MockitoBean JwtTokenProvider jwtTokenProvider;
    @MockitoBean TokenBlacklistService tokenBlacklistService;

    @Test
    @DisplayName("회원가입 성공 - 201 반환")
    void signup_success_returns201() throws Exception {
        mockMvc.perform(post("/auth/signup")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"user@bank.com","password":"Test1!abcd","name":"홍길동"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("회원가입 - 이메일 형식 불일치 시 400")
    void signup_invalidEmail_returns400() throws Exception {
        mockMvc.perform(post("/auth/signup")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"not-an-email","password":"Test1!abcd","name":"홍길동"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("회원가입 - 비밀번호 8자 미만 시 400")
    void signup_weakPassword_returns400() throws Exception {
        mockMvc.perform(post("/auth/signup")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"user@bank.com","password":"Pw1!","name":"홍길동"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("회원가입 - 이메일 중복 시 409")
    void signup_duplicateEmail_returns409() throws Exception {
        doThrow(new DuplicateEmailException()).when(authService).signup(any());

        mockMvc.perform(post("/auth/signup")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"dup@bank.com","password":"Test1!abcd","name":"홍길동"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("로그인 성공 - 200 + AT/RT 반환")
    void login_success_returns200WithTokens() throws Exception {
        given(authService.login(any()))
                .willReturn(new TokenResponse("access-token", "refresh-token"));

        mockMvc.perform(post("/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"user@bank.com","password":"Test1!abcd"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value("access-token"))
                .andExpect(jsonPath("$.data.refreshToken").value("refresh-token"));
    }

    @Test
    @DisplayName("로그인 - 잘못된 자격증명 시 401")
    void login_badCredentials_returns401() throws Exception {
        given(authService.login(any())).willThrow(new InvalidCredentialsException());

        mockMvc.perform(post("/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"user@bank.com","password":"wrong"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("토큰 갱신 - 탈취 의심 RT 재사용 시 401")
    void refresh_reusedToken_returns401() throws Exception {
        given(authService.refresh(any()))
                .willThrow(new InvalidTokenException("재사용된 RT — 탈취 의심, 전체 세션 폐기"));

        mockMvc.perform(post("/auth/refresh")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"already-used-token"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("로그아웃 - 인증된 사용자 200 반환")
    void logout_withAuth_returns200() throws Exception {
        mockMvc.perform(post("/auth/logout")
                        .with(csrf())
                        .with(authentication(AUTH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}
