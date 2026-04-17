package com.bank.exception;

import com.bank.entity.AccountStatus;

public class AccountNotActiveException extends RuntimeException {

    private final AccountStatus status;

    public AccountNotActiveException(AccountStatus status) {
        super(switch (status) {
            case FROZEN -> "동결된 계좌는 거래할 수 없습니다.";
            case DORMANT -> "휴면 계좌는 거래할 수 없습니다. 계좌를 먼저 활성화해주세요.";
            default -> "계좌 상태가 정상이 아닙니다.";
        });
        this.status = status;
    }

    public AccountStatus getStatus() {
        return status;
    }
}
