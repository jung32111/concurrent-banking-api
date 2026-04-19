package com.bank.repository;

import com.bank.entity.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {

    Optional<IdempotencyKey> findByIdempotencyKey(String key);

    /**
     * INSERT IGNORE: 중복 키 감지 시 갭 락 없이 조용히 스킵한다.
     * 일반 INSERT는 중복 시 공유 락(S-lock)을 획득해 여러 트랜잭션이 동시에
     * 같은 키에 INSERT 시도하면 데드락이 발생한다.
     * INSERT IGNORE는 이 락 없이 0 rows affected를 반환한다.
     *
     * @return 삽입된 행 수 (1: 선점 성공, 0: 이미 존재)
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "INSERT IGNORE INTO idempotency_keys (idempotency_key, request_hash, created_at, updated_at) " +
                   "VALUES (:key, :hash, NOW(), NOW())",
           nativeQuery = true)
    int insertIgnore(@Param("key") String key, @Param("hash") String requestHash);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM IdempotencyKey k WHERE k.createdAt < :threshold")
    int deleteByCreatedAtBefore(@Param("threshold") LocalDateTime threshold);
}
