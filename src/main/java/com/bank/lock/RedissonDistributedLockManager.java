package com.bank.lock;

import com.bank.exception.LockAcquisitionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * Redis(Redisson) 기반 분산 락 매니저.
 *
 * - 다중 애플리케이션 인스턴스 환경에서 **같은 계좌 자원에 대한 동시 접근을 직렬화** 한다.
 * - 두 개 계좌를 동시에 락 걸어야 하는 이체 시나리오에서는 **항상 계좌번호 사전순**으로
 *   락을 획득해 데드락을 방지한다 ({@link #executeWithMultiLock}).
 * - DB 레벨의 PESSIMISTIC_WRITE 락은 그대로 유지해 **이중 방어(layered locking)** 를 구성한다.
 *   (분산 락 획득 실패/만료 시에도 DB 락이 최후의 정합성 방어선)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedissonDistributedLockManager implements DistributedLockManager {

    private static final String LOCK_PREFIX = "lock:account:";

    private final RedissonClient redissonClient;

    @Override
    public <T> T executeWithLock(String key, long waitSeconds, long leaseSeconds, Callable<T> action) {
        RLock lock = redissonClient.getLock(LOCK_PREFIX + key);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(waitSeconds, leaseSeconds, TimeUnit.SECONDS);
            if (!acquired) {
                throw new LockAcquisitionException("분산 락 획득 실패: " + key);
            }
            return action.call();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LockAcquisitionException("분산 락 대기 중 인터럽트: " + key);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public <T> T executeWithMultiLock(String key1, String key2, long waitSeconds, long leaseSeconds, Callable<T> action) {
        // 데드락 방지: 항상 사전순으로 먼저 나오는 키를 먼저 획득
        String first = key1.compareTo(key2) <= 0 ? key1 : key2;
        String second = key1.compareTo(key2) <= 0 ? key2 : key1;

        return executeWithLock(first, waitSeconds, leaseSeconds, () ->
                executeWithLock(second, waitSeconds, leaseSeconds, action));
    }
}
