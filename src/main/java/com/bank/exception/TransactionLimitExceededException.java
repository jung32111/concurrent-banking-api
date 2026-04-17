package com.bank.exception;

import java.math.BigDecimal;

public class TransactionLimitExceededException extends RuntimeException {

    public enum LimitType {
        PER_TRANSACTION,
        PER_DAY
    }

    private final LimitType limitType;
    private final BigDecimal limit;
    private final BigDecimal attempted;

    public TransactionLimitExceededException(LimitType limitType, BigDecimal limit, BigDecimal attempted) {
        super(buildMessage(limitType, limit, attempted));
        this.limitType = limitType;
        this.limit = limit;
        this.attempted = attempted;
    }

    private static String buildMessage(LimitType type, BigDecimal limit, BigDecimal attempted) {
        return switch (type) {
            case PER_TRANSACTION -> String.format("1회 이체 한도(%s원)를 초과했습니다. 요청 금액: %s원", limit.toPlainString(), attempted.toPlainString());
            case PER_DAY -> String.format("일일 이체 한도(%s원)를 초과했습니다. 오늘 누적 포함 시도: %s원", limit.toPlainString(), attempted.toPlainString());
        };
    }

    public LimitType getLimitType() { return limitType; }
    public BigDecimal getLimit() { return limit; }
    public BigDecimal getAttempted() { return attempted; }
}
