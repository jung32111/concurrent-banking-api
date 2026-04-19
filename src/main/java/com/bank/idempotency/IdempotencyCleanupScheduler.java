package com.bank.idempotency;

import com.bank.repository.IdempotencyKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyCleanupScheduler {

    private static final Duration RETENTION = Duration.ofHours(24);

    private final IdempotencyKeyRepository idempotencyKeyRepository;

    @Transactional
    @Scheduled(cron = "0 0 3 * * *")
    public void cleanupOldKeys() {
        LocalDateTime threshold = LocalDateTime.now().minus(RETENTION);
        int deleted = idempotencyKeyRepository.deleteByCreatedAtBefore(threshold);
        log.info("[IdempotencyCleanup] {}시간 이전 키 {}건 삭제", RETENTION.toHours(), deleted);
    }
}
