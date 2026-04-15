package com.bank.accountservice.service;

import com.bank.accountservice.dto.TransferRequest;
import com.bank.accountservice.dto.TransferResponse;
import com.bank.accountservice.entity.Account;
import com.bank.accountservice.entity.Transaction;
import com.bank.accountservice.entity.TransactionType;
import com.bank.accountservice.entity.AuditAction;
import com.bank.accountservice.exception.AccountNotFoundException;
import com.bank.accountservice.exception.InsufficientBalanceException;
import com.bank.accountservice.exception.UnauthorizedAccessException;
import com.bank.accountservice.repository.AccountRepository;
import com.bank.accountservice.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransferService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final AuditLogService auditLogService;

    @Transactional
    public TransferResponse transfer(TransferRequest request, Long userId) {
        log.info("[SERVICE] TransferService.transfer() - 이체 시작");
        validateSameAccount(request);  //같은 계좌로 입금 방지

        String firstAccountNumber = request.fromAccountNumber().compareTo(request.toAccountNumber()) <= 0
                ? request.fromAccountNumber() : request.toAccountNumber();
        String secondAccountNumber = request.fromAccountNumber().compareTo(request.toAccountNumber()) <= 0
                ? request.toAccountNumber() : request.fromAccountNumber();

        Account firstLocked = accountRepository.findByAccountNumberWithLock(firstAccountNumber)
                .orElseThrow(AccountNotFoundException::new);
        Account secondLocked = accountRepository.findByAccountNumberWithLock(secondAccountNumber)
                .orElseThrow(AccountNotFoundException::new);

        Account fromAccount = request.fromAccountNumber().equals(firstAccountNumber) ? firstLocked : secondLocked;
        Account toAccount = request.toAccountNumber().equals(firstAccountNumber) ? firstLocked : secondLocked;

        validateOwner(fromAccount, userId);
        validateSufficientBalance(fromAccount, request.amount());

        fromAccount.transferOut(request.amount());
        toAccount.transferIn(request.amount());

        Transaction transferOut = Transaction.builder()
                .account(fromAccount)
                .amount(request.amount())
                .type(TransactionType.TRANSFER_OUT)
                .description("이체 출금 -> " + toAccount.getAccountNumber())
                .balanceAfterTransaction(fromAccount.getBalance())
                .build();

        Transaction transferIn = Transaction.builder()
                .account(toAccount)
                .amount(request.amount())
                .type(TransactionType.TRANSFER_IN)
                .description("이체 입금 <- " + fromAccount.getAccountNumber())
                .balanceAfterTransaction(toAccount.getBalance())
                .build();

        Transaction savedTransferOut = transactionRepository.save(transferOut);
        transactionRepository.save(transferIn);

        auditLogService.record(userId, AuditAction.TRANSFER, request.fromAccountNumber(), request.amount());

        return new TransferResponse(
                fromAccount.getAccountNumber(),
                fromAccount.getBalance(),
                toAccount.getAccountNumber(),
                toAccount.getBalance(),
                savedTransferOut.getCreatedAt()
        );
    }

    private void validateSameAccount(TransferRequest request) {
        if (request.fromAccountNumber().equals(request.toAccountNumber())) {
            throw new IllegalArgumentException("출금 계좌와 입금 계좌는 달라야 합니다.");
        }
    }

    private void validateOwner(Account account, Long userId) {
        if (userId == null || account.getUser() == null || !account.getUser().getId().equals(userId)) {
            throw new UnauthorizedAccessException();
        }
    }

    private void validateSufficientBalance(Account fromAccount, BigDecimal amount) {
        if (fromAccount.getBalance().compareTo(amount) < 0) {
            throw new InsufficientBalanceException();
        }
    }
}

