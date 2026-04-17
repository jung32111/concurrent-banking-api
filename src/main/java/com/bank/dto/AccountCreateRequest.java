// AccountCreateRequest.java
package com.bank.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
public class AccountCreateRequest {
    @NotBlank(message = "예금주명은 필수입니다.")
    private String ownerName;
}