package com.bank.accountservice.dto;

import com.bank.accountservice.entity.Account;
import com.bank.accountservice.entity.AccountStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
//계좌 조회 응답
public class AccountResponse {
    private Long id;
    private String accountNumber;
    private String ownerName;
    private BigDecimal balance;
    private AccountStatus status;
    private LocalDateTime createdAt;


    public static AccountResponse from(Account account) {
        return AccountResponse.builder()
                .id(account.getId())
                .accountNumber(account.getAccountNumber())
                .ownerName(account.getOwnerName())
                .balance(account.getBalance())
                .status(account.getStatus())
                .createdAt(account.getCreatedAt())
                .build();
    }
}