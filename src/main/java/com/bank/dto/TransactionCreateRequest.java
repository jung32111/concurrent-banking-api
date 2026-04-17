// TransactionCreateRequest.java
package com.bank.dto;

import com.bank.entity.TransactionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
@Builder
@Getter
public class TransactionCreateRequest {
    @NotBlank
    private String accountNumber;

    @NotNull
    @Positive
    private BigDecimal amount;

    @NotNull
    private TransactionType type;
    private String description;


}