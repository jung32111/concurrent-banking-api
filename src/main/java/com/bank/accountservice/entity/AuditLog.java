package com.bank.accountservice.entity;

import com.bank.accountservice.domain.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditLog extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuditAction action;

    // 마스킹된 계좌번호 (ex. 100-****5678)
    private String accountNumber;

    @Column(precision = 19, scale = 2)
    private BigDecimal amount;

    private String ipAddress;

    @Builder
    public AuditLog(Long userId, AuditAction action, String accountNumber, BigDecimal amount, String ipAddress) {
        this.userId = userId;
        this.action = action;
        this.accountNumber = accountNumber;
        this.amount = amount;
        this.ipAddress = ipAddress;
    }
}
