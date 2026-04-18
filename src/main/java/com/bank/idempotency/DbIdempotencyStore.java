package com.bank.idempotency;

import com.bank.entity.IdempotencyKey;
import com.bank.exception.IdempotencyHashMismatchException;
import com.bank.repository.IdempotencyKeyRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Repository
@RequiredArgsConstructor
public class DbIdempotencyStore implements IdempotencyStore {

    private final IdempotencyKeyRepository repository;
    private final EntityManager entityManager;

    /**
     * UNIQUE 제약에 기대어 선점·조회를 한 번에 처리한다.
     * 1) INSERT 시도 (response_body=NULL 상태로)
     *    - 성공 → 최초 처리자 (Fresh)
     * 2) UNIQUE 위반 → 이미 존재하는 레코드 조회
     *    - 해시 다르면 422
     *    - response_body NULL → 처리 중 → 409
     *    - response_body 존재 → 저장된 응답 재생
     */
    @Override
    @Transactional
    public GetOrCreateResult getOrCreate(String key, String requestHash) {
        try {
            repository.saveAndFlush(IdempotencyKey.forNewRequest(key, requestHash));
            return new GetOrCreateResult.Fresh();
        } catch (DataIntegrityViolationException e) {
            log.warn("[Idempotency] DataIntegrityViolation key={} message={} rootCause={}",
                    key, e.getMessage(),
                    e.getMostSpecificCause() != null ? e.getMostSpecificCause().getMessage() : "null");
            entityManager.clear();
        }

        IdempotencyKey existing = repository.findByIdempotencyKey(key)
                .orElseThrow(() -> new IllegalStateException(
                        "UNIQUE 위반 직후 레코드가 사라짐: " + key));

        if (!existing.getRequestHash().equals(requestHash)) {
            throw new IdempotencyHashMismatchException();
        }

        if (!existing.isCompleted()) {
            return new GetOrCreateResult.InProgress();
        }

        return new GetOrCreateResult.Replay(existing.getHttpStatus(), existing.getResponseBody());
    }

    @Override
    @Transactional
    public void saveResponse(String key, int httpStatus, String responseBody) {
        IdempotencyKey existing = repository.findByIdempotencyKey(key)
                .orElseThrow(() -> new IllegalStateException(
                        "saveResponse 시점에 레코드 없음: " + key));
        existing.fillResponse(httpStatus, responseBody);
    }
}
