package com.bank.lock;

import java.util.concurrent.Callable;

public interface DistributedLockManager {

    <T> T executeWithLock(String key, long waitSeconds, long leaseSeconds, Callable<T> action);

    <T> T executeWithMultiLock(String key1, String key2, long waitSeconds, long leaseSeconds, Callable<T> action);
}
