package com.bank.exception;

public class IdempotencyHashMismatchException extends RuntimeException {

    public IdempotencyHashMismatchException() {
        super("동일한 Idempotency-Key로 다른 요청 바디가 감지되었습니다.");
    }
}
