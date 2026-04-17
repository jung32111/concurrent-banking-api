package com.bank.exception;

public class UnauthorizedAccessException extends RuntimeException {
    public UnauthorizedAccessException() {
        super("해당 계좌에 접근 권한이 없습니다.");
    }
}

