package com.bank.dto;

import com.bank.entity.Transaction;
import com.bank.entity.TransactionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Builder
@AllArgsConstructor
public class TransactionResponse {
    private Long id;
    private String accountNumber;
    private BigDecimal amount;
    private TransactionType type;
    private BigDecimal balanceAfterTransaction;
    private String description;
    private LocalDateTime createdAt;

    public static TransactionResponse from(Transaction tx) {
        return TransactionResponse.builder()
                .id(tx.getId())
                .accountNumber(tx.getAccount().getAccountNumber())
                .amount(tx.getAmount())
                .type(tx.getType())
                .balanceAfterTransaction(tx.getBalanceAfterTransaction())
                .description(tx.getDescription())
                .createdAt(tx.getCreatedAt())
                .build();
    }
}
