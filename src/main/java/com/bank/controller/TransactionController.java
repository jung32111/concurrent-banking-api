package com.bank.controller;

import com.bank.dto.ApiResponse;
import com.bank.dto.TransactionCreateRequest;
import com.bank.dto.TransactionResponse;
import com.bank.service.TransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Transaction", description = "입금 / 출금 / 거래 내역 조회 (Idempotency-Key 필수)")
@RestController
@RequestMapping("/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;

    @Operation(summary = "입금 / 출금", description = "Idempotency-Key 헤더 필수. 출금은 거래 한도 적용")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "거래 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "유효성 검증 실패 / Idempotency-Key 누락"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "동결/휴면 계좌"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "잔액 부족 또는 거래 한도 초과")
    })
    @PostMapping
    public ResponseEntity<ApiResponse<TransactionResponse>> createTransaction(
            @Valid @RequestBody TransactionCreateRequest request,
            @AuthenticationPrincipal Long userId) {
        TransactionResponse response = transactionService.createTransaction(request, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @Operation(summary = "거래 내역 조회", description = "페이지네이션 지원 (기본 page=0, size=20)")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 계좌가 아님")
    })
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
