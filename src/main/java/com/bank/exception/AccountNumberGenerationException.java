package com.bank.exception;

public class AccountNumberGenerationException extends RuntimeException {
    public AccountNumberGenerationException() {
        super("계좌번호 생성에 실패했습니다. 잠시 후 다시 시도해주세요.");
    }
}
