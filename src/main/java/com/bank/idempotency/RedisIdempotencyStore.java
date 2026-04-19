package com.bank.idempotency;

import com.bank.exception.IdempotencyHashMismatchException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis 기반 멱등성 Store.
 * SET NX(원자적 선점) + TTL 자동 만료로 동작한다.
 * DB Store가 @Primary이므로 테스트나 설정 변경 시에만 활성화된다.
 *
 * DB Store와의 트레이드오프:
 * - 장점: 락 없는 원자적 선점(SET NX), 별도 스케줄러 없이 TTL 자동 만료, 응답 속도 빠름
 * - 단점: Redis 재시작 시 선점 데이터 유실 가능, 이체 트랜잭션과 다른 저장소라 정합성 경계 분리
 */
@Component
@RequiredArgsConstructor
public class RedisIdempotencyStore implements IdempotencyStore {

    private static final String IN_PROGRESS_PREFIX = "IN_PROGRESS:";
    private static final Duration IN_PROGRESS_TTL = Duration.ofSeconds(30);
    private static final Duration RESPONSE_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public GetOrCreateResult getOrCreate(String key, String requestHash) {
        // SET NX: 원자적 선점 시도. 성공 시 Fresh.
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(key, IN_PROGRESS_PREFIX + requestHash, IN_PROGRESS_TTL);

        if (Boolean.TRUE.equals(acquired)) {
            return new GetOrCreateResult.Fresh();
        }

        String stored = redisTemplate.opsForValue().get(key);

        if (stored == null) {
            return new GetOrCreateResult.InProgress();
        }

        if (stored.startsWith(IN_PROGRESS_PREFIX)) {
            String storedHash = stored.substring(IN_PROGRESS_PREFIX.length());
            if (!storedHash.equals(requestHash)) {
                throw new IdempotencyHashMismatchException();
            }
            return new GetOrCreateResult.InProgress();
        }

        // 완료된 응답 JSON
        try {
            StoredEntry entry = objectMapper.readValue(stored, StoredEntry.class);
            if (!entry.requestHash().equals(requestHash)) {
                throw new IdempotencyHashMismatchException();
            }
            return new GetOrCreateResult.Replay(entry.httpStatus(), entry.responseBody());
        } catch (IdempotencyHashMismatchException e) {
            throw e;
        } catch (Exception e) {
            return new GetOrCreateResult.InProgress();
        }
    }

    @Override
    public void saveResponse(String key, String requestHash, int httpStatus, String responseBody) {
        try {
            String json = objectMapper.writeValueAsString(
                    new StoredEntry(requestHash, httpStatus, responseBody));
            redisTemplate.opsForValue().set(key, json, RESPONSE_TTL);
        } catch (Exception e) {
            throw new IllegalStateException("Redis 응답 직렬화 실패", e);
        }
    }

    public void delete(String key) {
        redisTemplate.delete(key);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record StoredEntry(String requestHash, int httpStatus, String responseBody) {}
}
