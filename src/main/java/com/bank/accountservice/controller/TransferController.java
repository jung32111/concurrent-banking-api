package com.bank.accountservice.controller;

import com.bank.accountservice.dto.ApiResponse;
import com.bank.accountservice.dto.TransferRequest;
import com.bank.accountservice.dto.TransferResponse;
import com.bank.accountservice.service.TransferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Transfer", description = "계좌 이체 (Idempotency-Key 필수)")
@RestController
@RequestMapping("/transfers")
@RequiredArgsConstructor
public class TransferController {

    private final TransferService transferService;

    @Operation(summary = "계좌 이체", description = "Redisson 분산락 + DB 비관적락 이중 방어. Idempotency-Key 헤더 필수")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "이체 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "유효성 검증 실패 / Idempotency-Key 누락"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "분산 락 획득 실패 (동시 처리 중) 또는 동결/휴면 계좌"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "거래 한도 초과 (1회 1,000만 / 1일 5,000만)")
    })
    @PostMapping
    public ResponseEntity<ApiResponse<TransferResponse>> transfer(
            @Valid @RequestBody TransferRequest request,
            @AuthenticationPrincipal Long userId) {
        TransferResponse response = transferService.transfer(request, userId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
