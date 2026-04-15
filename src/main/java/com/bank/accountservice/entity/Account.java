package com.bank.accountservice.entity;

import com.bank.accountservice.domain.BaseTimeEntity;
import com.bank.accountservice.exception.InsufficientBalanceException;
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


    @Builder
    public Account(String accountNumber, String ownerName, User user) {
        this.accountNumber = accountNumber;
        this.ownerName = ownerName;
        this.user = user;
        this.balance = BigDecimal.ZERO;
    }


    //입금
    public void deposit(BigDecimal amount) {
        validateAmount(amount);
        this.balance = this.balance.add(amount);
    }

    //출금
    public void withdraw(BigDecimal amount) {
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

    //입금 값 검증
    private void validateAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("금액은 0보다 커야 합니다.");   //입금시 돈이 0원보다 큰지
        }
    }
}