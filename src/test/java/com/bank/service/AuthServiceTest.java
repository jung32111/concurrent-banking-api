package com.bank.service;

import com.bank.dto.LoginRequest;
import com.bank.dto.SignupRequest;
import com.bank.dto.TokenRequest;
import com.bank.dto.TokenResponse;
import com.bank.entity.RefreshToken;
import com.bank.entity.User;
import com.bank.exception.DuplicateEmailException;
import com.bank.exception.InvalidCredentialsException;
import com.bank.exception.InvalidTokenException;
import com.bank.repository.RefreshTokenRepository;
import com.bank.repository.UserRepository;
import com.bank.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.ArgumentCaptor;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private AuditLogService auditLogService;

    @InjectMocks private AuthService authService;

    @Test
    void signup_success_savesUserWithEncodedPassword() {
        SignupRequest request = signupRequest("a@b.com", "Pw1!abcd", "홍길동");
        when(userRepository.existsByEmail("a@b.com")).thenReturn(false);
        when(passwordEncoder.encode("Pw1!abcd")).thenReturn("ENCODED");

        authService.signup(request);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo("a@b.com");
        assertThat(captor.getValue().getPassword()).isEqualTo("ENCODED");
    }

    @Test
    void signup_duplicateEmail_throws() {
        SignupRequest request = signupRequest("a@b.com", "Pw1!abcd", "홍");
        when(userRepository.existsByEmail("a@b.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.signup(request))
                .isInstanceOf(DuplicateEmailException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void login_success_returnsTokens() {
        User user = createUser(10L, "a@b.com", "ENC");
        LoginRequest req = loginRequest("a@b.com", "plain");
        when(userRepository.findByEmail("a@b.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("plain", "ENC")).thenReturn(true);
        when(jwtTokenProvider.generateToken(eq(10L), eq("a@b.com"), any())).thenReturn("AT");

        TokenResponse res = authService.login(req);

        assertThat(res.getAccessToken()).isEqualTo("AT");
        assertThat(res.getRefreshToken()).isNotBlank();
        verify(refreshTokenRepository).save(any(RefreshToken.class));
    }

    @Test
    void login_wrongPassword_throwsInvalidCredentials() {
        User user = createUser(10L, "a@b.com", "ENC");
        when(userRepository.findByEmail("a@b.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("plain", "ENC")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(loginRequest("a@b.com", "plain")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void login_unknownEmail_throwsInvalidCredentials() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> authService.login(loginRequest("x@x.com", "pw")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void refresh_reusedToken_deletesAllAndThrows() {
        RefreshToken used = RefreshToken.builder()
                .token("rt-1").userId(10L)
                .expiresAt(LocalDateTime.now().plusDays(1))
                .build();
        used.markAsUsed();
        when(refreshTokenRepository.findByToken("rt-1")).thenReturn(Optional.of(used));

        TokenRequest req = tokenRequest("rt-1");
        assertThatThrownBy(() -> authService.refresh(req))
                .isInstanceOf(InvalidTokenException.class);

        verify(refreshTokenRepository).deleteByUserId(10L);
    }

    @Test
    void refresh_expiredToken_deletesAllAndThrows() {
        RefreshToken expired = RefreshToken.builder()
                .token("rt-2").userId(10L)
                .expiresAt(LocalDateTime.now().minusDays(1))
                .build();
        when(refreshTokenRepository.findByToken("rt-2")).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> authService.refresh(tokenRequest("rt-2")))
                .isInstanceOf(InvalidTokenException.class);
        verify(refreshTokenRepository).deleteByUserId(10L);
    }

    @Test
    void refresh_valid_rotatesTokenAndIssuesNew() {
        RefreshToken valid = RefreshToken.builder()
                .token("rt-3").userId(10L)
                .expiresAt(LocalDateTime.now().plusDays(1))
                .build();
        User user = createUser(10L, "a@b.com", "ENC");
        when(refreshTokenRepository.findByToken("rt-3")).thenReturn(Optional.of(valid));
        when(userRepository.findById(10L)).thenReturn(Optional.of(user));
        when(jwtTokenProvider.generateToken(eq(10L), eq("a@b.com"), any())).thenReturn("AT-NEW");

        TokenResponse res = authService.refresh(tokenRequest("rt-3"));

        assertThat(res.getAccessToken()).isEqualTo("AT-NEW");
        assertThat(res.getRefreshToken()).isNotBlank().isNotEqualTo("rt-3");
        assertThat(valid.isUsed()).isTrue();
    }

    @Test
    void logout_deletesAllRefreshTokens() {
        authService.logout(10L);
        verify(refreshTokenRepository).deleteByUserId(10L);
        verify(auditLogService).record(eq(10L), any(), any(), any());
    }

    private User createUser(Long id, String email, String password) {
        User user = User.builder().email(email).password(password).name("n").build();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private SignupRequest signupRequest(String email, String pw, String name) {
        SignupRequest r = new SignupRequest();
        ReflectionTestUtils.setField(r, "email", email);
        ReflectionTestUtils.setField(r, "password", pw);
        ReflectionTestUtils.setField(r, "name", name);
        return r;
    }

    private LoginRequest loginRequest(String email, String pw) {
        LoginRequest r = new LoginRequest();
        ReflectionTestUtils.setField(r, "email", email);
        ReflectionTestUtils.setField(r, "password", pw);
        return r;
    }

    private TokenRequest tokenRequest(String rt) {
        TokenRequest r = new TokenRequest();
        ReflectionTestUtils.setField(r, "refreshToken", rt);
        return r;
    }
}
