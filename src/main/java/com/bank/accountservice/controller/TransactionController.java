package com.bank.accountservice.controller;

import com.bank.accountservice.dto.ApiResponse;
import com.bank.accountservice.dto.TransactionCreateRequest;
import com.bank.accountservice.dto.TransactionResponse;
import com.bank.accountservice.service.TransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;

    // 거래 기록 (입금/출금)
    @PostMapping
    public ResponseEntity<ApiResponse<TransactionResponse>> createTransaction(
            @Valid @RequestBody TransactionCreateRequest request,
            @AuthenticationPrincipal Long userId) {
        TransactionResponse response = transactionService.createTransaction(request, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    // 거래 내역 조회 (페이지네이션)
    @GetMapping("/{accountNumber}")
    public ResponseEntity<ApiResponse<Page<TransactionResponse>>> getTransactions(
            @PathVariable String accountNumber,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal Long userId) {
        Page<TransactionResponse> result = transactionService.getTransactions(
                accountNumber, userId, PageRequest.of(page, size));
        return ResponseEntity.ok(ApiResponse.ok(result));
    }
}