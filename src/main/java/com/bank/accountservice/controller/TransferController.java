package com.bank.accountservice.controller;

import com.bank.accountservice.dto.ApiResponse;
import com.bank.accountservice.dto.TransferRequest;
import com.bank.accountservice.dto.TransferResponse;
import com.bank.accountservice.service.TransferService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/transfers")
@RequiredArgsConstructor
public class TransferController {

    private final TransferService transferService;

    @PostMapping
    public ResponseEntity<ApiResponse<TransferResponse>> transfer(
            @Valid @RequestBody TransferRequest request,
            @AuthenticationPrincipal Long userId) {
        TransferResponse response = transferService.transfer(request, userId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}

