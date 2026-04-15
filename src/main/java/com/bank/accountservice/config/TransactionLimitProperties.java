package com.bank.accountservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * 거래 한도 정책 값. {@code bank.transaction.limit.*} 로 주입.
 *
 * <p>실제 시중은행의 비대면 기본 한도(1회 1,000만원 / 1일 5,000만원)를 기본값으로 설정.
 * 테스트에서는 낮은 값으로 오버라이드한다.
 */
@ConfigurationProperties(prefix = "bank.transaction.limit")
public class TransactionLimitProperties {

    private BigDecimal perTransaction = new BigDecimal("10000000");
    private BigDecimal perDay = new BigDecimal("50000000");

    public BigDecimal getPerTransaction() { return perTransaction; }
    public void setPerTransaction(BigDecimal perTransaction) { this.perTransaction = perTransaction; }

    public BigDecimal getPerDay() { return perDay; }
    public void setPerDay(BigDecimal perDay) { this.perDay = perDay; }
}
