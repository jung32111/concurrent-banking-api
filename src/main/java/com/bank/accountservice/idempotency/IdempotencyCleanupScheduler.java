package com.bank.accountservice.idempotency;

import com.bank.accountservice.repository.IdempotencyKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyCleanupScheduler {

    private final IdempotencyKeyRepository idempotencyKeyRepository;

    @Transactional
    @Scheduled(cron = "0 0 3 * * *")
    public void cleanupExpiredKeys() {
        int deleted = idempotencyKeyRepository.deleteByExpiredAtBefore(LocalDateTime.now());
        log.info("[IdempotencyCleanup] 만료 키 {}건 삭제 완료 - 실행 시각: {}", deleted, LocalDateTime.now());
    }
}

