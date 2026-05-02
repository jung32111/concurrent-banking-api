package com.bank.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenProviderTest {

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        provider = new JwtTokenProvider();
        ReflectionTestUtils.setField(provider, "secretKey",
                "test-256bit-secret-key-for-jwt-token-provider-1234");
        ReflectionTestUtils.setField(provider, "accessTokenExpirationMs", 86_400_000L);
    }

    @Test
    @DisplayName("토큰 생성 후 validateToken 은 true 반환")
    void generateToken_thenValidate_returnsTrue() {
        String token = provider.generateToken(1L, "user@bank.com");
        assertThat(provider.validateToken(token)).isTrue();
    }

    @Test
    @DisplayName("토큰에서 userId 정확히 추출")
    void generateToken_thenGetUserId_returnsCorrectId() {
        String token = provider.generateToken(42L, "user@bank.com");
        assertThat(provider.getUserId(token)).isEqualTo(42L);
    }

    @Test
    @DisplayName("만료된 토큰은 validateToken false 반환")
    void expiredToken_validateToken_returnsFalse() {
        ReflectionTestUtils.setField(provider, "accessTokenExpirationMs", -1000L);
        String expiredToken = provider.generateToken(1L, "user@bank.com");
        assertThat(provider.validateToken(expiredToken)).isFalse();
    }

    @Test
    @DisplayName("변조된 토큰은 validateToken false 반환")
    void tamperedToken_validateToken_returnsFalse() {
        String token = provider.generateToken(1L, "user@bank.com");
        String tampered = token + "tampered";
        assertThat(provider.validateToken(tampered)).isFalse();
    }

    @Test
    @DisplayName("빈 문자열 토큰은 validateToken false 반환")
    void emptyToken_validateToken_returnsFalse() {
        assertThat(provider.validateToken("")).isFalse();
    }

    @Test
    @DisplayName("다른 시크릿으로 서명된 토큰은 validateToken false 반환")
    void wrongSecretToken_validateToken_returnsFalse() {
        JwtTokenProvider other = new JwtTokenProvider();
        ReflectionTestUtils.setField(other, "secretKey",
                "completely-different-secret-key-for-testing-1234xx");
        ReflectionTestUtils.setField(other, "accessTokenExpirationMs", 86_400_000L);

        String otherToken = other.generateToken(1L, "user@bank.com");
        assertThat(provider.validateToken(otherToken)).isFalse();
    }
}
