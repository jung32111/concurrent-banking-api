package com.bank.entity;

import com.bank.domain.BaseTimeEntity;
import com.bank.exception.AccountNotActiveException;
import com.bank.exception.InsufficientBalanceException;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;


@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)

public class Account extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;


    @Column(unique = true, nullable = false, length = 20)
    private String accountNumber;

    @Column(nullable = false)
    private String ownerName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status;


    @Builder
    public Account(String accountNumber, String ownerName, User user) {
        this.accountNumber = accountNumber;
        this.ownerName = ownerName;
        this.user = user;
        this.balance = BigDecimal.ZERO;
        this.status = AccountStatus.ACTIVE;
    }


    //입금
    public void deposit(BigDecimal amount) {
        ensureTransactable();
        validateAmount(amount);
        this.balance = this.balance.add(amount);
    }

    //출금
    public void withdraw(BigDecimal amount) {
        ensureTransactable();
        validateAmount(amount);
        if (this.balance.compareTo(amount) < 0) {
            throw new InsufficientBalanceException();     //출금시 값이 부족할 경우 예외
        }
        this.balance = this.balance.subtract(amount);
    }

    public void transferOut(BigDecimal amount) {
        withdraw(amount);
    }

    public void transferIn(BigDecimal amount) {
        deposit(amount);
    }

    // ===== 상태 전이 =====

    /** 분실신고 등으로 계좌 동결. */
    public void freeze() {
        if (this.status == AccountStatus.FROZEN) return;
        this.status = AccountStatus.FROZEN;
    }

    /** 동결 해제 (FROZEN -> ACTIVE). DORMANT 에는 영향 없음. */
    public void unfreeze() {
        if (this.status != AccountStatus.FROZEN) {
            throw new IllegalStateException("동결 상태가 아닌 계좌는 해제할 수 없습니다.");
        }
        this.status = AccountStatus.ACTIVE;
    }

    /** 장기 미사용으로 휴면 전환. (보통 배치로 수행) */
    public void markDormant() {
        if (this.status == AccountStatus.FROZEN) {
            throw new IllegalStateException("동결 상태의 계좌는 휴면 처리할 수 없습니다.");
        }
        this.status = AccountStatus.DORMANT;
    }

    /** 휴면 계좌 재활성화. */
    public void activate() {
        if (this.status != AccountStatus.DORMANT) {
            throw new IllegalStateException("휴면 상태가 아닌 계좌는 활성화할 수 없습니다.");
        }
        this.status = AccountStatus.ACTIVE;
    }

    /** 거래 가능 상태인지 검증. ACTIVE 가 아니면 {@link AccountNotActiveException} 발생. */
    public void ensureTransactable() {
        if (this.status != AccountStatus.ACTIVE) {
            throw new AccountNotActiveException(this.status);
        }
    }

    //입금 값 검증
    private void validateAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("금액은 0보다 커야 합니다.");   //입금시 돈이 0원보다 큰지
        }
    }
}
