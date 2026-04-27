package com.bank.service;

import com.bank.entity.Account;
import com.bank.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계좌 저장을 별도 트랜잭션({@code REQUIRES_NEW})에서 시도한다.
 *
 * <p>호출자({@link AccountService#createAccount})는 계좌번호 충돌 시 새 번호로 재시도하는데,
 * 같은 트랜잭션 안에서 {@code DataIntegrityViolationException} 을 잡고 재호출하면
 * 트랜잭션이 이미 rollback-only 로 마킹돼 commit 시점에
 * {@code UnexpectedRollbackException} 이 발생한다.
 * INSERT 시도마다 새 트랜잭션을 열어 호출자가 안전하게 재시도할 수 있게 한다.
 */
@Component
@RequiredArgsConstructor
public class AccountInserter {

    private final AccountRepository accountRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Account tryInsert(Account account) {
        return accountRepository.save(account);
    }
}
