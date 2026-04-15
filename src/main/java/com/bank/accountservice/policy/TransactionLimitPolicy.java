package com.bank.accountservice.policy;

import com.bank.accountservice.config.TransactionLimitProperties;
import com.bank.accountservice.entity.Account;
import com.bank.accountservice.entity.TransactionType;
import com.bank.accountservice.exception.TransactionLimitExceededException;
import com.bank.accountservice.exception.TransactionLimitExceededException.LimitType;
import com.bank.accountservice.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;

/**
 * 출금성 거래(WITHDRAW, TRANSFER_OUT)에 대해 **1회 한도 / 일일 한도** 를 검증.
 *
 * <p>호출 전제:
 * <ul>
 *   <li>호출자가 이미 계좌 락(Redisson + DB PESSIMISTIC_WRITE)을 잡고 있어야 한다.</li>
 *   <li>호출자가 동일 트랜잭션에서 검증 후 실제 차감을 수행해야 한다.</li>
 * </ul>
 * 이 두 조건이 보장되면 **동시 이체 시 합계 race condition 없이 정확한 한도 적용**이 가능하다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionLimitPolicy {

    private static final Set<TransactionType> DEBIT_TYPES =
            EnumSet.of(TransactionType.WITHDRAW, TransactionType.TRANSFER_OUT);

    private final TransactionLimitProperties properties;
    private final TransactionRepository transactionRepository;
    private final Clock clock;

    public void validate(Account account, BigDecimal amount) {
        BigDecimal perTx = properties.getPerTransaction();
        if (amount.compareTo(perTx) > 0) {
            throw new TransactionLimitExceededException(LimitType.PER_TRANSACTION, perTx, amount);
        }

        LocalDate today = LocalDate.now(clock);
        BigDecimal todaySum = transactionRepository.sumAmountByAccountAndTypesAndCreatedAtBetween(
                account,
                DEBIT_TYPES,
                today.atStartOfDay(),
                today.plusDays(1).atStartOfDay()
        );
        BigDecimal projected = todaySum.add(amount);
        BigDecimal perDay = properties.getPerDay();
        if (projected.compareTo(perDay) > 0) {
            throw new TransactionLimitExceededException(LimitType.PER_DAY, perDay, projected);
        }
    }
}
