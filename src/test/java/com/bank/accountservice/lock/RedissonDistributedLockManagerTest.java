package com.bank.accountservice.lock;

import com.bank.accountservice.exception.LockAcquisitionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedissonDistributedLockManagerTest {

    @Mock
    private RedissonClient redissonClient;

    private RedissonDistributedLockManager lockManager;

    @BeforeEach
    void setUp() {
        lockManager = new RedissonDistributedLockManager(redissonClient);
    }

    @Test
    void executeWithLock_success_invokesActionAndReleasesLock() throws Exception {
        RLock lock = mock(RLock.class, "lock:account:A");
        when(redissonClient.getLock("lock:account:A")).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        String result = lockManager.executeWithLock("A", 3L, 5L, () -> "ok");

        assertThat(result).isEqualTo("ok");
        InOrder inOrder = inOrder(lock);
        inOrder.verify(lock).tryLock(3L, 5L, TimeUnit.SECONDS);
        inOrder.verify(lock).unlock();
    }

    @Test
    void executeWithLock_acquireFails_throwsLockAcquisitionExceptionAndDoesNotUnlock() throws Exception {
        RLock lock = mock(RLock.class, "lock:account:A");
        when(redissonClient.getLock("lock:account:A")).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        AtomicReference<Boolean> called = new AtomicReference<>(false);

        assertThatThrownBy(() -> lockManager.executeWithLock("A", 3L, 5L, () -> {
            called.set(true);
            return "never";
        }))
                .isInstanceOf(LockAcquisitionException.class)
                .hasMessageContaining("A");

        assertThat(called.get()).isFalse();
        verify(lock, never()).unlock();
    }

    @Test
    void executeWithLock_actionThrows_propagatesAndReleasesLock() throws Exception {
        RLock lock = mock(RLock.class, "lock:account:A");
        when(redissonClient.getLock("lock:account:A")).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        assertThatThrownBy(() -> lockManager.executeWithLock("A", 3L, 5L, () -> {
            throw new IllegalStateException("boom");
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

        verify(lock).unlock();
    }

    @Test
    void executeWithLock_interrupted_restoresFlagAndThrowsLockAcquisitionException() throws Exception {
        RLock lock = mock(RLock.class, "lock:account:A");
        when(redissonClient.getLock("lock:account:A")).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenThrow(new InterruptedException());

        try {
            assertThatThrownBy(() -> lockManager.executeWithLock("A", 3L, 5L, () -> "never"))
                    .isInstanceOf(LockAcquisitionException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            // clear flag to not affect other tests
            Thread.interrupted();
        }
    }

    @Test
    void executeWithLock_doesNotUnlockIfNotHeldByCurrentThread() throws Exception {
        RLock lock = mock(RLock.class, "lock:account:A");
        when(redissonClient.getLock("lock:account:A")).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(false);

        lockManager.executeWithLock("A", 3L, 5L, () -> "ok");

        verify(lock, never()).unlock();
    }

    @Test
    void executeWithMultiLock_acquiresInLexicographicOrderRegardlessOfInputOrder() throws Exception {
        RLock lockA = mock(RLock.class, "lock:account:A");
        RLock lockB = mock(RLock.class, "lock:account:B");
        when(redissonClient.getLock("lock:account:A")).thenReturn(lockA);
        when(redissonClient.getLock("lock:account:B")).thenReturn(lockB);
        when(lockA.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lockB.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lockA.isHeldByCurrentThread()).thenReturn(true);
        when(lockB.isHeldByCurrentThread()).thenReturn(true);

        lockManager.executeWithMultiLock("B", "A", 3L, 5L, () -> "ok");

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(redissonClient, org.mockito.Mockito.times(2)).getLock(keyCaptor.capture());
        assertThat(keyCaptor.getAllValues()).containsExactly("lock:account:A", "lock:account:B");

        // outer lock released last: verify A.unlock is called after B.unlock
        InOrder inOrder = inOrder(lockB, lockA);
        inOrder.verify(lockB).unlock();
        inOrder.verify(lockA).unlock();
    }

    @Test
    void executeWithMultiLock_innerLockFails_outerLockIsStillReleased() throws Exception {
        RLock lockA = mock(RLock.class, "lock:account:A");
        RLock lockB = mock(RLock.class, "lock:account:B");
        when(redissonClient.getLock("lock:account:A")).thenReturn(lockA);
        when(redissonClient.getLock("lock:account:B")).thenReturn(lockB);
        when(lockA.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lockB.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);
        when(lockA.isHeldByCurrentThread()).thenReturn(true);

        assertThatThrownBy(() -> lockManager.executeWithMultiLock("A", "B", 3L, 5L, () -> "never"))
                .isInstanceOf(LockAcquisitionException.class)
                .hasMessageContaining("B");

        verify(lockA).unlock();
        verify(lockB, never()).unlock();
    }

    private static <T> T mock(Class<T> type, String name) {
        return org.mockito.Mockito.mock(type, name);
    }
}
