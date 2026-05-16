package com.bank.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class LoginAttemptService {

    private static final String FAIL_KEY_PREFIX = "login:fail:";
    private static final String LOCK_KEY_PREFIX = "login:locked:";

    private final StringRedisTemplate stringRedisTemplate;

    @Value("${bank.login.max-attempts:5}")
    private int maxAttempts;

    @Value("${bank.login.lockout-duration-minutes:30}")
    private int lockoutDurationMinutes;

    public boolean isLocked(String email) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(LOCK_KEY_PREFIX + email));
    }

    public void recordFailure(String email) {
        String failKey = FAIL_KEY_PREFIX + email;
        Long count = stringRedisTemplate.opsForValue().increment(failKey);
        if (count != null && count == 1) {
            stringRedisTemplate.expire(failKey, Duration.ofMinutes(lockoutDurationMinutes));
        }
        if (count != null && count >= maxAttempts) {
            stringRedisTemplate.opsForValue().set(LOCK_KEY_PREFIX + email, "1", Duration.ofMinutes(lockoutDurationMinutes));
            stringRedisTemplate.delete(failKey);
        }
    }

    public void recordSuccess(String email) {
        stringRedisTemplate.delete(FAIL_KEY_PREFIX + email);
    }
}
