package com.bank.service;

import com.bank.dto.TransferRequest;
import com.bank.dto.TransferResponse;
import com.bank.entity.Account;
import com.bank.entity.Transaction;
import com.bank.entity.TransactionType;
import com.bank.entity.AuditAction;
import com.bank.exception.AccountNotFoundException;
import com.bank.exception.InsufficientBalanceException;
import com.bank.exception.UnauthorizedAccessException;
import com.bank.lock.DistributedLockManager;
import com.bank.policy.TransactionLimitPolicy;
import com.bank.repository.AccountRepository;
import com.bank.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;

/**
 * 이체 서비스.
 *
 * 동시성 전략 (이중 방어):
 *   1) 외부(분산) 락 — Redisson {@link DistributedLockManager}
 *      다중 인스턴스 환경에서 같은 계좌에 대한 동시 요청을 직렬화한다.
 *      두 계좌 락은 사전순 정렬 후 획득 → 데드락 방지.
 *   2) 내부(DB) 락 — {@link AccountRepository#findByAccountNumberWithLock(String)} (PESSIMISTIC_WRITE)
 *      분산 락이 만료/실패했을 때의 최후 방어선.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransferService {

    private static final long LOCK_WAIT_SECONDS = 3L;
    private static final long LOCK_LEASE_SECONDS = 5L;

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final AuditLogService auditLogService;
    private final DistributedLockManager lockManager;
    private final TransactionTemplate transactionTemplate;
    private final TransactionLimitPolicy transactionLimitPolicy;

    public TransferResponse transfer(TransferRequest request, Long userId) {
        log.info("[SERVICE] TransferService.transfer() - 이체 시작");
        validateSameAccount(request);

        return lockManager.executeWithMultiLock(
                request.fromAccountNumber(),
                request.toAccountNumber(),
                LOCK_WAIT_SECONDS,
                LOCK_LEASE_SECONDS,
                () -> transactionTemplate.execute(status -> doTransfer(request, userId))
        );
    }

    private TransferResponse doTransfer(TransferRequest request, Long userId) {
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
        transactionLimitPolicy.validate(fromAccount, request.amount());
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
