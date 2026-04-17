package com.bank.service;

import com.bank.dto.LoginRequest;
import com.bank.dto.SignupRequest;
import com.bank.dto.TokenRequest;
import com.bank.dto.TokenResponse;
import com.bank.entity.AuditAction;
import com.bank.entity.RefreshToken;
import com.bank.entity.User;
import com.bank.exception.DuplicateEmailException;
import com.bank.exception.InvalidCredentialsException;
import com.bank.exception.InvalidTokenException;
import com.bank.exception.UserNotFoundException;
import com.bank.repository.RefreshTokenRepository;
import com.bank.repository.UserRepository;
import com.bank.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final int REFRESH_TOKEN_VALIDITY_DAYS = 7;

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final AuditLogService auditLogService;

    @Transactional
    public void signup(SignupRequest request) {
        log.info("[SERVICE] AuthService.signup() - 회원가입 처리 시작");
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateEmailException();
        }

        User user = User.builder()
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .name(request.getName())
                .build();

        userRepository.save(user);
        auditLogService.record(user.getId(), AuditAction.SIGNUP, null, null);
    }

    @Transactional
    public TokenResponse login(LoginRequest request) {
        log.info("[SERVICE] AuthService.login() - 로그인 처리 시작");
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new InvalidCredentialsException();
        }

        String accessToken = jwtTokenProvider.generateToken(user.getId(), user.getEmail());
        String refreshToken = issueRefreshToken(user.getId());
        auditLogService.record(user.getId(), AuditAction.LOGIN, null, null);
        return new TokenResponse(accessToken, refreshToken);
    }

    /**
     * RTR(Refresh Token Rotation) 패턴:
     * - 정상 요청: 기존 RT 사용 처리(used=true) → 새 AT + 새 RT 발급
     * - 이미 사용된 RT 재요청: 해당 유저의 모든 RT 삭제 (보안 이벤트)
     */
    @Transactional
    public TokenResponse refresh(TokenRequest request) {
        log.info("[SERVICE] AuthService.refresh() - 토큰 갱신 처리 시작");
        RefreshToken refreshToken = refreshTokenRepository.findByToken(request.getRefreshToken())
                .orElseThrow(() -> new InvalidTokenException("유효하지 않은 리프레시 토큰입니다."));

        if (refreshToken.isUsed()) {
            // 이미 소비된 토큰 재사용 → 토큰 탈취 의심, 해당 유저 전체 RT 삭제
            refreshTokenRepository.deleteByUserId(refreshToken.getUserId());
            throw new InvalidTokenException("이미 사용된 리프레시 토큰입니다. 보안을 위해 재로그인이 필요합니다.");
        }

        if (refreshToken.isExpired()) {
            refreshTokenRepository.deleteByUserId(refreshToken.getUserId());
            throw new InvalidTokenException("만료된 리프레시 토큰입니다.");
        }

        Long userId = refreshToken.getUserId();
        User user = userRepository.findById(userId)
                .orElseThrow(UserNotFoundException::new);

        // 기존 RT를 사용됨으로 표시 (RTR)
        refreshToken.markAsUsed();

        String accessToken = jwtTokenProvider.generateToken(user.getId(), user.getEmail());
        String newRefreshToken = issueRefreshToken(userId);
        return new TokenResponse(accessToken, newRefreshToken);
    }

    @Transactional
    public void logout(Long userId) {
        log.info("[SERVICE] AuthService.logout() - 로그아웃 처리 시작");
        refreshTokenRepository.deleteByUserId(userId);
        auditLogService.record(userId, AuditAction.LOGOUT, null, null);
    }

    private String issueRefreshToken(Long userId) {
        String tokenValue = UUID.randomUUID().toString();
        RefreshToken refreshToken = RefreshToken.builder()
                .token(tokenValue)
                .userId(userId)
                .expiresAt(LocalDateTime.now().plusDays(REFRESH_TOKEN_VALIDITY_DAYS))
                .build();
        refreshTokenRepository.save(refreshToken);
        return tokenValue;
    }
}
