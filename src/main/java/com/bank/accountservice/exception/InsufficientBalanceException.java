package com.bank.accountservice.exception;

public class InsufficientBalanceException extends RuntimeException {
    public InsufficientBalanceException() {
        super("잔액이 부족합니다.");
    }
}

