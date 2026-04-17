package com.bank.service;

import com.bank.dto.TransactionCreateRequest;
import com.bank.dto.TransactionResponse;
import com.bank.entity.Account;
import com.bank.entity.Transaction;
import com.bank.entity.TransactionType;
import com.bank.exception.AccountNotFoundException;
import com.bank.exception.UnauthorizedAccessException;
import com.bank.entity.AuditAction;
import com.bank.policy.TransactionLimitPolicy;
import com.bank.repository.AccountRepository;
import com.bank.repository.TransactionRepository;
import com.bank.util.LogMaskingUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.math.BigDecimal;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final AuditLogService auditLogService;
    private final TransactionLimitPolicy transactionLimitPolicy;

    //거래와 거래내역을 트랜잭션으로 묶어서 원자성 보장
    @Transactional
    public TransactionResponse createTransaction(TransactionCreateRequest request, Long userId) {
        log.info("[SERVICE] TransactionService.createTransaction() - {} 처리 시작", request.getType());
        Account account = accountRepository.findByAccountNumberWithLock(request.getAccountNumber())
                .orElseThrow(AccountNotFoundException::new);

        validateOwner(account, userId);

        if (request.getType() == TransactionType.DEPOSIT) {
            account.deposit(request.getAmount());
        } else {
            transactionLimitPolicy.validate(account, request.getAmount());
            account.withdraw(request.getAmount());
        }

        Transaction transaction = Transaction.builder()
                .account(account)
                .amount(request.getAmount())
                .type(request.getType())
                .description(request.getDescription())
                .balanceAfterTransaction(account.getBalance())
                .build();

        Transaction savedTransaction = transactionRepository.save(transaction);

        AuditAction action = request.getType() == TransactionType.DEPOSIT ? AuditAction.DEPOSIT : AuditAction.WITHDRAW;
        auditLogService.record(userId, action, request.getAccountNumber(), request.getAmount());

        return TransactionResponse.from(savedTransaction);
    }

    @Transactional(readOnly = true)
    public Page<TransactionResponse> getTransactions(String accountNumber, Long userId, Pageable pageable) {
        log.info("[SERVICE] 거래 내역 조회 시작 - accountNumber: {}", LogMaskingUtil.maskAccountNumber(accountNumber));
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(AccountNotFoundException::new);

        validateOwner(account, userId);

        return transactionRepository.findByAccountOrderByCreatedAtDesc(account, pageable)
                .map(TransactionResponse::from);
    }

    public TransactionResponse deposit(String accountNumber, BigDecimal amount, String description, Long userId) {
        log.info("[SERVICE] TransactionService.deposit() - 입금 처리 시작");
        TransactionCreateRequest request = TransactionCreateRequest.builder()
                .accountNumber(accountNumber)
                .type(TransactionType.DEPOSIT)
                .amount(amount)
                .description(description)
                .build();
        return createTransaction(request, userId);
    }

    public TransactionResponse withdraw(String accountNumber, BigDecimal amount, String description, Long userId) {
        log.info("[SERVICE] TransactionService.withdraw() - 출금 처리 시작");
        TransactionCreateRequest request = TransactionCreateRequest.builder()
                .accountNumber(accountNumber)
                .type(TransactionType.WITHDRAW)
                .amount(amount)
                .description(description)
                .build();
        return createTransaction(request, userId);
    }

    private void validateOwner(Account account, Long userId) {
        if (userId == null || account.getUser() == null || !account.getUser().getId().equals(userId)) {
            throw new UnauthorizedAccessException();
        }
    }
}
