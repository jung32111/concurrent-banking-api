package com.bank.entity;

import com.bank.exception.AccountNotActiveException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountStatusTest {

    private Account newAccount() {
        return Account.builder()
                .accountNumber("100-00000001")
                .ownerName("tester")
                .user(null)
                .build();
    }

    @Test
    void newAccount_defaultsToActive() {
        Account account = newAccount();
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void freeze_blocksDepositAndWithdraw() {
        Account account = newAccount();
        account.deposit(new BigDecimal("1000.00"));

        account.freeze();

        assertThat(account.getStatus()).isEqualTo(AccountStatus.FROZEN);
        assertThatThrownBy(() -> account.deposit(new BigDecimal("100")))
                .isInstanceOf(AccountNotActiveException.class);
        assertThatThrownBy(() -> account.withdraw(new BigDecimal("100")))
                .isInstanceOf(AccountNotActiveException.class);
        assertThatThrownBy(() -> account.transferOut(new BigDecimal("100")))
                .isInstanceOf(AccountNotActiveException.class);
        assertThatThrownBy(() -> account.transferIn(new BigDecimal("100")))
                .isInstanceOf(AccountNotActiveException.class);
    }

    @Test
    void dormant_blocksAllTransactions() {
        Account account = newAccount();
        account.markDormant();

        assertThat(account.getStatus()).isEqualTo(AccountStatus.DORMANT);
        assertThatThrownBy(() -> account.deposit(new BigDecimal("100")))
                .isInstanceOf(AccountNotActiveException.class);
        assertThatThrownBy(() -> account.transferOut(new BigDecimal("100")))
                .isInstanceOf(AccountNotActiveException.class);
    }

    @Test
    void unfreeze_restoresActiveState() {
        Account account = newAccount();
        account.freeze();

        account.unfreeze();

        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        account.deposit(new BigDecimal("100")); // no exception
    }

    @Test
    void unfreeze_whenNotFrozen_throwsIllegalState() {
        Account account = newAccount();
        assertThatThrownBy(account::unfreeze).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void activate_restoresDormantToActive() {
        Account account = newAccount();
        account.markDormant();

        account.activate();

        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        account.deposit(new BigDecimal("100"));
    }

    @Test
    void activate_whenNotDormant_throwsIllegalState() {
        Account account = newAccount();
        assertThatThrownBy(account::activate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void markDormant_whenFrozen_throwsIllegalState() {
        Account account = newAccount();
        account.freeze();
        assertThatThrownBy(account::markDormant).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void freeze_whenAlreadyFrozen_isIdempotent() {
        Account account = newAccount();
        account.freeze();
        account.freeze();
        assertThat(account.getStatus()).isEqualTo(AccountStatus.FROZEN);
    }
}
