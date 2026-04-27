package com.bank.service;

import com.bank.dto.AccountCreateRequest;
import com.bank.dto.AccountResponse;
import com.bank.entity.Account;
import com.bank.entity.User;
import com.bank.exception.AccountNotFoundException;
import com.bank.exception.AccountNumberGenerationException;
import com.bank.exception.UnauthorizedAccessException;
import com.bank.exception.UserNotFoundException;
import com.bank.entity.AuditAction;
import com.bank.repository.AccountRepository;
import com.bank.repository.UserRepository;
import com.bank.util.LogMaskingUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    private static final int MAX_ACCOUNT_NUMBER_RETRY = 10;

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final AccountInserter accountInserter;

    /**
     * 랜덤 계좌번호 생성 + UNIQUE 충돌 시 재시도.
     *
     * <p>의도적으로 메서드 자체에는 {@code @Transactional} 을 붙이지 않는다.
     * INSERT 시도마다 새 트랜잭션이 열려야 충돌 후 재시도가 가능하기 때문이다.
     * 실제 INSERT 는 {@link AccountInserter#tryInsert}({@code REQUIRES_NEW}) 가 담당한다.
     */
    public AccountResponse createAccount(AccountCreateRequest request, Long userId) {
        log.info("[SERVICE] AccountService.createAccount() - 계좌 생성 시작");
        User user = userRepository.findById(userId)
                .orElseThrow(UserNotFoundException::new);

        for (int attempt = 0; attempt < MAX_ACCOUNT_NUMBER_RETRY; attempt++) {
            Account account = Account.builder()
                    .ownerName(request.getOwnerName())
                    .accountNumber(generateAccountNumber())
                    .user(user)
                    .build();
            try {
                Account saved = accountInserter.tryInsert(account);
                auditLogService.record(userId, AuditAction.ACCOUNT_CREATE, saved.getAccountNumber(), null);
                return AccountResponse.from(saved);
            } catch (DataIntegrityViolationException e) {
                log.warn("[SERVICE] 계좌번호 충돌 - 재시도 {}/{}", attempt + 1, MAX_ACCOUNT_NUMBER_RETRY);
            }
        }

        throw new AccountNumberGenerationException();
    }

    // 내 계좌 목록 조회
    @Transactional(readOnly = true)
    public List<AccountResponse> getMyAccounts(Long userId) {
        log.info("[SERVICE] AccountService.getMyAccounts() - 계좌 목록 조회 시작");
        return accountRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(AccountResponse::from)
                .collect(java.util.stream.Collectors.toList());
    }

    // 계좌 조회 - DTO 반환
    @Transactional(readOnly = true)
    public AccountResponse getAccount(String accountNumber, Long userId) {
        log.info("[SERVICE] 계좌 조회 시작 - accountNumber: {}", LogMaskingUtil.maskAccountNumber(accountNumber));
        Account account = findAccount(accountNumber);
        validateOwner(account, userId);
        return AccountResponse.from(account);
    }

    // 잔액 조회
    @Transactional(readOnly = true)
    public BigDecimal getBalance(String accountNumber, Long userId) {
        log.info("[SERVICE] 잔액 조회 시작 - accountNumber: {}", LogMaskingUtil.maskAccountNumber(accountNumber));
        Account account = findAccount(accountNumber);
        validateOwner(account, userId);
        return account.getBalance();
    }

    /** 계좌 동결 (분실신고/보안 이유로 소유자가 직접 차단). */
    @Transactional
    public AccountResponse freeze(String accountNumber, Long userId) {
        log.info("[SERVICE] 계좌 동결 요청 - accountNumber: {}", LogMaskingUtil.maskAccountNumber(accountNumber));
        Account account = findAccount(accountNumber);
        validateOwner(account, userId);
        account.freeze();
        auditLogService.record(userId, AuditAction.ACCOUNT_FREEZE, accountNumber, null);
        return AccountResponse.from(account);
    }

    /** 동결 해제. */
    @Transactional
    public AccountResponse unfreeze(String accountNumber, Long userId) {
        log.info("[SERVICE] 계좌 동결 해제 요청 - accountNumber: {}", LogMaskingUtil.maskAccountNumber(accountNumber));
        Account account = findAccount(accountNumber);
        validateOwner(account, userId);
        account.unfreeze();
        auditLogService.record(userId, AuditAction.ACCOUNT_UNFREEZE, accountNumber, null);
        return AccountResponse.from(account);
    }

    /** 휴면 계좌 활성화. */
    @Transactional
    public AccountResponse activate(String accountNumber, Long userId) {
        log.info("[SERVICE] 휴면 계좌 활성화 요청 - accountNumber: {}", LogMaskingUtil.maskAccountNumber(accountNumber));
        Account account = findAccount(accountNumber);
        validateOwner(account, userId);
        account.activate();
        auditLogService.record(userId, AuditAction.ACCOUNT_ACTIVATE, accountNumber, null);
        return AccountResponse.from(account);
    }

    // 내부 전용 - 엔티티 직접 반환
    public Account getAccountEntity(String accountNumber, Long userId) {
        log.info("[SERVICE] 계좌 엔티티 조회 시작 - accountNumber: {}", LogMaskingUtil.maskAccountNumber(accountNumber));
        Account account = findAccount(accountNumber);
        validateOwner(account, userId);
        return account;
    }

    private Account findAccount(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(AccountNotFoundException::new);
    }

    private void validateOwner(Account account, Long userId) {
        if (userId == null || account.getUser() == null || !account.getUser().getId().equals(userId)) {
            throw new UnauthorizedAccessException();
        }
    }

    //계좌번호 생성
    private String generateAccountNumber() {
        return "100-" + String.format("%08d",
                ThreadLocalRandom.current().nextInt(100_000_000));
    }
}