package com.bank.idempotency;

import com.bank.repository.IdempotencyKeyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class IdempotencyKeyInserter {

    private final IdempotencyKeyRepository repository;

    /**
     * INSERT IGNORE로 멱등성 키를 선점한다.
     * 중복 키 감지 시 갭 락 없이 스킵 → 동시 INSERT 데드락 없음.
     *
     * @return true: 선점 성공(Fresh), false: 이미 존재(Duplicate)
     */
    @Transactional
    public boolean tryInsert(String key, String requestHash) {
        return repository.insertIgnore(key, requestHash) > 0;
    }
}
