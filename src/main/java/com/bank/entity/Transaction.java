package com.bank.entity;

import com.bank.domain.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;


@Entity
@Table(
    name = "transaction",
    indexes = {
        // TransactionLimitPolicy의 일일 한도 SUM 쿼리용 복합 인덱스.
        // WHERE account_id = ? AND type IN (WITHDRAW, TRANSFER_OUT)
        //   AND created_at >= ? AND created_at < ?
        // EXPLAIN: rows 1195 → 553 (Using where 제거, Using index condition).
        @Index(name = "idx_tx_acc_type_createdat",
               columnList = "account_id, type, created_at")
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Transaction extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)

    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balanceAfterTransaction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionType type;

    private String description;


    @Builder
    public Transaction(Account account, BigDecimal amount, TransactionType type, String description,BigDecimal balanceAfterTransaction) {

        if (account == null) {
            throw new IllegalArgumentException("계좌는 필수입니다."); //계좌 검증
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("거래 금액은 0보다 커야 합니다.");  //거래금액 검증
        }
        if (balanceAfterTransaction == null || balanceAfterTransaction.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("거래 후 잔액은 0 이상이어야 합니다.");       //거래금액과 거래 후 잔액 검증
        }
        this.account = account;
        this.amount = amount;
        this.type = type;
        this.description = description;
        this.balanceAfterTransaction = balanceAfterTransaction;
    }
}