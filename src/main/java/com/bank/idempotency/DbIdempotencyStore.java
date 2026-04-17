package com.bank.idempotency;

import com.bank.entity.IdempotencyKey;
import com.bank.repository.IdempotencyKeyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class DbIdempotencyStore implements IdempotencyStore {

    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    private final IdempotencyKeyRepository repository;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public Optional<String> getResponse(String key) {
        return repository.findByIdempotencyKey(key)
                .filter(k -> !k.isExpired(LocalDateTime.now()))
                .map(k -> {
                    try {
                        return objectMapper.writeValueAsString(
                                new StoredResponse(k.getHttpStatus(), k.getResponseBody())
                        );
                    } catch (Exception e) {
                        throw new IllegalStateException("응답 직렬화 실패", e);
                    }
                });
    }

    @Override
    @Transactional
    public void save(String key, String response) {
        LocalDateTime expiredAt = LocalDateTime.now().plus(DEFAULT_TTL);

        StoredResponse storedResponse;
        try {
            storedResponse = objectMapper.readValue(response, StoredResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException("응답 역직렬화 실패", e);
        }

        // requestHash 필드(NOT NULL)는 응답 JSON의 해시로 채운다
        String requestHash = IdempotencyHashUtil.hash(response);

        try {
            IdempotencyKey entity = IdempotencyKey.builder()
                    .idempotencyKey(key)
                    .requestHash(requestHash)
                    .responseBody(storedResponse.responseBody())
                    .httpStatus(storedResponse.httpStatus())
                    .expiredAt(expiredAt)
                    .build();

            repository.save(entity);

        } catch (DataIntegrityViolationException e) {
            // 동시 요청으로 이미 저장된 경우 — 멱등성 보장, 무시
            entityManager.clear();
        }
    }
}
