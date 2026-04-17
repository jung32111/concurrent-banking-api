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

    //특정 멱등성 키로 레코드 조회
    Optional<IdempotencyKey> findByIdempotencyKey(String key);

    //만료된 레코드 삭제
    @Transactional
    //clearAutomatically: 삭제후 1차캐시를 삭제해 다음 조회시 DB에서 새 데이터 가져오게 함
    //flushAutomatically: 삭제하기전에 남아있을 변경사항을 DB에 먼저 반영
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM IdempotencyKey k WHERE k.expiredAt < :now")
    int deleteByExpiredAtBefore(@Param("now") LocalDateTime now);
}

