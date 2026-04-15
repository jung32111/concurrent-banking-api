package com.bank.accountservice.controller;

import com.bank.accountservice.dto.AccountCreateRequest;
import com.bank.accountservice.dto.AccountResponse;
import com.bank.accountservice.dto.ApiResponse;
import com.bank.accountservice.service.AccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/accounts")
@RequiredArgsConstructor
@Validated
public class AccountController {

    private final AccountService accountService;

    // 내 계좌 목록 조회
    @GetMapping
    public ResponseEntity<ApiResponse<List<AccountResponse>>> getMyAccounts(
            @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(accountService.getMyAccounts(userId)));
    }

    // 계좌 생성
    @PostMapping
    public ResponseEntity<ApiResponse<AccountResponse>> createAccount(
            @Valid @RequestBody AccountCreateRequest request,
            @AuthenticationPrincipal Long userId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(accountService.createAccount(request, userId)));
    }

    // 계좌 조회
    @GetMapping("/{accountNumber}")
    public ResponseEntity<ApiResponse<AccountResponse>> getAccount(
            @PathVariable String accountNumber,
            @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(accountService.getAccount(accountNumber, userId)));
    }

    // 잔액 조회
    @GetMapping("/{accountNumber}/balance")
    public ResponseEntity<ApiResponse<BigDecimal>> getBalance(
            @PathVariable String accountNumber,
            @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(accountService.getBalance(accountNumber, userId)));
    }

    // 계좌 동결 (분실신고 등)
    @PostMapping("/{accountNumber}/freeze")
    public ResponseEntity<ApiResponse<AccountResponse>> freeze(
            @PathVariable String accountNumber,
            @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(accountService.freeze(accountNumber, userId)));
    }

    // 계좌 동결 해제
    @PostMapping("/{accountNumber}/unfreeze")
    public ResponseEntity<ApiResponse<AccountResponse>> unfreeze(
            @PathVariable String accountNumber,
            @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(accountService.unfreeze(accountNumber, userId)));
    }

    // 휴면 계좌 활성화
    @PostMapping("/{accountNumber}/activate")
    public ResponseEntity<ApiResponse<AccountResponse>> activate(
            @PathVariable String accountNumber,
            @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(accountService.activate(accountNumber, userId)));
    }

}