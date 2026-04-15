package com.bank.accountservice.service;

import com.bank.accountservice.dto.TransactionCreateRequest;
import com.bank.accountservice.dto.TransactionResponse;
import com.bank.accountservice.entity.Account;
import com.bank.accountservice.entity.Transaction;
import com.bank.accountservice.entity.TransactionType;
import com.bank.accountservice.entity.User;
import com.bank.accountservice.exception.AccountNotFoundException;
import com.bank.accountservice.exception.InsufficientBalanceException;
import com.bank.accountservice.exception.UnauthorizedAccessException;
import com.bank.accountservice.policy.TransactionLimitPolicy;
import com.bank.accountservice.repository.AccountRepository;
import com.bank.accountservice.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock private AccountRepository accountRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private TransactionLimitPolicy transactionLimitPolicy;

    @InjectMocks private TransactionService transactionService;

    @Test
    void deposit_success_increasesBalanceAndSavesTransaction() {
        Account account = createAccount("100-00000001", createUser(1L), new BigDecimal("1000.00"));
        when(accountRepository.findByAccountNumberWithLock("100-00000001")).thenReturn(Optional.of(account));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        TransactionResponse res = transactionService.deposit("100-00000001", new BigDecimal("500.00"), "입금", 1L);

        assertThat(account.getBalance()).isEqualByComparingTo("1500.00");
        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(captor.getValue().getBalanceAfterTransaction()).isEqualByComparingTo("1500.00");
        assertThat(res.getBalanceAfterTransaction()).isEqualByComparingTo("1500.00");
    }

    @Test
    void withdraw_success_decreasesBalance() {
        Account account = createAccount("100-00000001", createUser(1L), new BigDecimal("1000.00"));
        when(accountRepository.findByAccountNumberWithLock("100-00000001")).thenReturn(Optional.of(account));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        transactionService.withdraw("100-00000001", new BigDecimal("300.00"), "출금", 1L);

        assertThat(account.getBalance()).isEqualByComparingTo("700.00");
    }

    @Test
    void withdraw_insufficientBalance_throws() {
        Account account = createAccount("100-00000001", createUser(1L), new BigDecimal("100.00"));
        when(accountRepository.findByAccountNumberWithLock("100-00000001")).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> transactionService.withdraw("100-00000001", new BigDecimal("500.00"), "x", 1L))
                .isInstanceOf(InsufficientBalanceException.class);
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void createTransaction_notOwner_throws() {
        Account account = createAccount("100-00000001", createUser(1L), new BigDecimal("1000.00"));
        when(accountRepository.findByAccountNumberWithLock("100-00000001")).thenReturn(Optional.of(account));

        TransactionCreateRequest req = TransactionCreateRequest.builder()
                .accountNumber("100-00000001")
                .amount(new BigDecimal("100.00"))
                .type(TransactionType.DEPOSIT)
                .description("x")
                .build();

        assertThatThrownBy(() -> transactionService.createTransaction(req, 999L))
                .isInstanceOf(UnauthorizedAccessException.class);
    }

    @Test
    void createTransaction_accountNotFound_throws() {
        when(accountRepository.findByAccountNumberWithLock(any())).thenReturn(Optional.empty());
        TransactionCreateRequest req = TransactionCreateRequest.builder()
                .accountNumber("100-99999999")
                .amount(new BigDecimal("100.00"))
                .type(TransactionType.DEPOSIT)
                .build();

        assertThatThrownBy(() -> transactionService.createTransaction(req, 1L))
                .isInstanceOf(AccountNotFoundException.class);
    }

    private User createUser(Long id) {
        User u = User.builder().email("a@b.com").password("pw").name("n").build();
        ReflectionTestUtils.setField(u, "id", id);
        return u;
    }

    private Account createAccount(String num, User user, BigDecimal balance) {
        Account a = Account.builder().accountNumber(num).ownerName("o").user(user).build();
        a.deposit(balance);
        return a;
    }
}
