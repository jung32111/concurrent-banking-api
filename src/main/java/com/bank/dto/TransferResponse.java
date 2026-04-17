package com.bank.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TransferResponse(
        String fromAccountNumber,
        BigDecimal fromBalanceAfter,
        String toAccountNumber,
        BigDecimal toBalanceAfter,
        LocalDateTime transferredAt
) {
}

