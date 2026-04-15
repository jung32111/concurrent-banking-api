package com.bank.accountservice.policy;

import com.bank.accountservice.config.TransactionLimitProperties;
import com.bank.accountservice.entity.Account;
import com.bank.accountservice.entity.TransactionType;
import com.bank.accountservice.exception.TransactionLimitExceededException;
import com.bank.accountservice.exception.TransactionLimitExceededException.LimitType;
import com.bank.accountservice.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransactionLimitPolicyTest {

    private TransactionLimitProperties props;
    private TransactionRepository transactionRepository;
    private Clock fixedClock;
    private TransactionLimitPolicy policy;

    private final Account account = Account.builder()
            .accountNumber("100-00000001").ownerName("o").user(null).build();

    @BeforeEach
    void setUp() {
        props = new TransactionLimitProperties();
        props.setPerTransaction(new BigDecimal("10000"));
        props.setPerDay(new BigDecimal("30000"));

        transactionRepository = mock(TransactionRepository.class);

        // 2026-04-16 고정
        fixedClock = Clock.fixed(
                LocalDate.of(2026, 4, 16).atStartOfDay(ZoneId.systemDefault()).toInstant(),
                ZoneId.systemDefault());

        policy = new TransactionLimitPolicy(props, transactionRepository, fixedClock);
    }

    @Test
    void validate_underBothLimits_passes() {
        when(transactionRepository.sumAmountByAccountAndTypesAndCreatedAtBetween(
                eq(account), any(), any(), any())).thenReturn(BigDecimal.ZERO);

        assertThatCode(() -> policy.validate(account, new BigDecimal("5000"))).doesNotThrowAnyException();
    }

    @Test
    void validate_perTransactionExceeded_throws() {
        assertThatThrownBy(() -> policy.validate(account, new BigDecimal("10001")))
                .isInstanceOfSatisfying(TransactionLimitExceededException.class,
                        e -> org.assertj.core.api.Assertions.assertThat(e.getLimitType()).isEqualTo(LimitType.PER_TRANSACTION));
    }

    @Test
    void validate_perTransactionExactlyEqual_passes() {
        when(transactionRepository.sumAmountByAccountAndTypesAndCreatedAtBetween(
                eq(account), any(), any(), any())).thenReturn(BigDecimal.ZERO);

        assertThatCode(() -> policy.validate(account, new BigDecimal("10000"))).doesNotThrowAnyException();
    }

    @Test
    void validate_dailySumPlusAmountExceeds_throws() {
        when(transactionRepository.sumAmountByAccountAndTypesAndCreatedAtBetween(
                eq(account), any(), any(), any())).thenReturn(new BigDecimal("25000"));

        assertThatThrownBy(() -> policy.validate(account, new BigDecimal("6000")))
                .isInstanceOfSatisfying(TransactionLimitExceededException.class,
                        e -> org.assertj.core.api.Assertions.assertThat(e.getLimitType()).isEqualTo(LimitType.PER_DAY));
    }

    @Test
    void validate_queriesTodayRangeAndDebitTypesOnly() {
        when(transactionRepository.sumAmountByAccountAndTypesAndCreatedAtBetween(
                eq(account), any(), any(), any())).thenReturn(BigDecimal.ZERO);

        policy.validate(account, new BigDecimal("100"));

        LocalDateTime todayStart = LocalDate.of(2026, 4, 16).atStartOfDay();
        verify(transactionRepository).sumAmountByAccountAndTypesAndCreatedAtBetween(
                eq(account),
                argThatContainsExactly(TransactionType.WITHDRAW, TransactionType.TRANSFER_OUT),
                eq(todayStart),
                eq(todayStart.plusDays(1))
        );
    }

    private static Collection<TransactionType> argThatContainsExactly(TransactionType... types) {
        return org.mockito.ArgumentMatchers.argThat(c ->
                c != null && c.size() == types.length && c.containsAll(java.util.Arrays.asList(types)));
    }
}
