package com.bank.accountservice.idempotency;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Primary
@Component
@RequiredArgsConstructor
public class RedisIdempotencyStore implements IdempotencyStore {

    // 내부 전용 상수 — 인터페이스에 노출하지 않음
    private static final String IN_PROGRESS = "IN_PROGRESS";
    private static final Duration IN_PROGRESS_TTL = Duration.ofSeconds(30);  //선점유지시간
    private static final Duration RESPONSE_TTL = Duration.ofHours(24); //응답 캐싱시간

    private final StringRedisTemplate redisTemplate;

    /**
     * getResponse() 한 번으로 멱등성 판단 + race condition 방지까지 처리
     *
     * 1) setIfAbsent(key, "IN_PROGRESS", 30s) — atomic 선점 시도
     *    - 성공(키 없었음) → Optional.empty() 반환 (최초 처리)
     *    - 실패(키 이미 존재) → 저장된 값 그대로 반환
     *       - "IN_PROGRESS" : 다른 요청이 처리 중
     *       - 실제 JSON 응답 : 이전에 완료된 응답
     */
    @Override
    public Optional<String> getResponse(String key) {
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(key, IN_PROGRESS, IN_PROGRESS_TTL);

        if (Boolean.TRUE.equals(acquired)) {
            // 선점 성공 → 최초 처리
            return Optional.empty();
        }

        // 선점 실패 → 저장된 값 반환 (IN_PROGRESS 또는 실제 응답)
        return Optional.ofNullable(redisTemplate.opsForValue().get(key));
    }

    /**
     * 처리 완료된 JSON 응답으로 덮어쓰고 TTL을 24시간으로 갱신한다.
     * TTL을 반드시 재설정해야 IN_PROGRESS(30s)의 남은 시간이 아닌
     * 정상 24시간 TTL이 적용된다.
     */
    @Override
    public void save(String key, String response) {
        redisTemplate.opsForValue().set(key, response, RESPONSE_TTL);
    }

    /**
     * 처리 실패(예외·비2xx) 시 IN_PROGRESS 키를 즉시 삭제한다.
     * 30초 TTL 자동 만료에만 의존하지 않고 클라이언트가 즉시 재시도할 수 있도록 한다.
     */
    @Override
    public void delete(String key) {
        redisTemplate.delete(key);
    }
}
