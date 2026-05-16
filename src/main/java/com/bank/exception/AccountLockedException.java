package com.bank.exception;

public class AccountLockedException extends RuntimeException {

    public AccountLockedException(int lockoutDurationMinutes) {
        super("로그인 시도 횟수 초과로 계정이 잠겼습니다. " + lockoutDurationMinutes + "분 후 다시 시도해주세요.");
    }
}
