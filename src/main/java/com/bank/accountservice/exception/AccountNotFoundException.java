package com.bank.accountservice.exception;

public class AccountNotFoundException extends RuntimeException {
    public AccountNotFoundException() {
        super("존재하지 않는 계좌입니다.");
    }
}

