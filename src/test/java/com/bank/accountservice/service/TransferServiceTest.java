package com.bank.accountservice.service;

import com.bank.accountservice.dto.TransferRequest;
import com.bank.accountservice.dto.TransferResponse;
import com.bank.accountservice.entity.Account;
import com.bank.accountservice.entity.Transaction;
import com.bank.accountservice.entity.TransactionType;
import com.bank.accountservice.entity.User;
import com.bank.accountservice.exception.AccountNotActiveException;
import com.bank.accountservice.exception.AccountNotFoundException;
import com.bank.accountservice.exception.InsufficientBalanceException;
import com.bank.accountservice.exception.UnauthorizedAccessException;
import com.bank.accountservice.lock.DistributedLockManager;
import com.bank.accountservice.policy.TransactionLimitPolicy;
import com.bank.accountservice.repository.AccountRepository;
import com.bank.accountservice.repository.TransactionRepository;
import com.bank.accountservice.service.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private DistributedLockManager lockManager;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private TransactionLimitPolicy transactionLimitPolicy;

    @InjectMocks
    private TransferService transferService;

    @BeforeEach
    void setUpLockAndTx() {
        when(lockManager.executeWithMultiLock(any(), any(), anyLong(), anyLong(), any()))
                .thenAnswer(invocation -> ((Callable<?>) invocation.getArgument(4)).call());
        when(transactionTemplate.execute(any()))
                .thenAnswer(invocation -> ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
    }

    @Test
    void transfer_success_updatesBalancesAndSavesTwoLogsInSortedLockOrder() {
        User user = createUser(1L);
        Account from = createAccount("200-00000002", user, new BigDecimal("10000.00"));
        Account to = createAccount("100-00000001", user, new BigDecimal("3000.00"));

        when(accountRepository.findByAccountNumberWithLock("100-00000001")).thenReturn(Optional.of(to));
        when(accountRepository.findByAccountNumberWithLock("200-00000002")).thenReturn(Optional.of(from));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TransferRequest request = new TransferRequest("200-00000002", "100-00000001", new BigDecimal("2500.00"));

        TransferResponse response = transferService.transfer(request, 1L);

        assertThat(response.fromBalanceAfter()).isEqualByComparingTo("7500.00");
        assertThat(response.toBalanceAfter()).isEqualByComparingTo("5500.00");
        assertThat(response.fromAccountNumber()).isEqualTo("200-00000002");
        assertThat(response.toAccountNumber()).isEqualTo("100-00000001");

        InOrder inOrder = inOrder(accountRepository);
        inOrder.verify(accountRepository).findByAccountNumberWithLock("100-00000001");
        inOrder.verify(accountRepository).findByAccountNumberWithLock("200-00000002");

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository, times(2)).save(captor.capture());

        List<Transaction> logs = captor.getAllValues();
        assertThat(logs).hasSize(2);
        assertThat(logs.get(0).getType()).isEqualTo(TransactionType.TRANSFER_OUT);
        assertThat(logs.get(0).getBalanceAfterTransaction()).isEqualByComparingTo("7500.00");
        assertThat(logs.get(1).getType()).isEqualTo(TransactionType.TRANSFER_IN);
        assertThat(logs.get(1).getBalanceAfterTransaction()).isEqualByComparingTo("5500.00");
    }

    @Test
    void transfer_insufficientBalance_throwsInsufficientBalanceException() {
        User user = createUser(1L);
        Account from = createAccount("100-00000001", user, new BigDecimal("1000.00"));
        Account to = createAccount("200-00000002", user, new BigDecimal("500.00"));

        when(accountRepository.findByAccountNumberWithLock("100-00000001")).thenReturn(Optional.of(from));
        when(accountRepository.findByAccountNumberWithLock("200-00000002")).thenReturn(Optional.of(to));

        TransferRequest request = new TransferRequest("100-00000001", "200-00000002", new BigDecimal("1500.00"));

        assertThatThrownBy(() -> transferService.transfer(request, 1L))
                .isInstanceOf(InsufficientBalanceException.class);
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    @Test
    void transfer_accountNotFound_throwsAccountNotFoundException() {
        when(accountRepository.findByAccountNumberWithLock("100-00000001")).thenReturn(Optional.empty());

        TransferRequest request = new TransferRequest("100-00000001", "200-00000002", new BigDecimal("100.00"));

        assertThatThrownBy(() -> transferService.transfer(request, 1L))
                .isInstanceOf(AccountNotFoundException.class);
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    @Test
    void transfer_fromAccountFrozen_throwsAccountNotActiveException() {
        User user = createUser(1L);
        Account from = createAccount("100-00000001", user, new BigDecimal("1000.00"));
        Account to = createAccount("200-00000002", user, new BigDecimal("500.00"));
        from.freeze();

        when(accountRepository.findByAccountNumberWithLock("100-00000001")).thenReturn(Optional.of(from));
        when(accountRepository.findByAccountNumberWithLock("200-00000002")).thenReturn(Optional.of(to));

        TransferRequest request = new TransferRequest("100-00000001", "200-00000002", new BigDecimal("100.00"));

        assertThatThrownBy(() -> transferService.transfer(request, 1L))
                .isInstanceOf(AccountNotActiveException.class);
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    @Test
    void transfer_toAccountDormant_throwsAccountNotActiveException() {
        User user = createUser(1L);
        Account from = createAccount("100-00000001", user, new BigDecimal("1000.00"));
        Account to = createAccount("200-00000002", user, new BigDecimal("500.00"));
        to.markDormant();

        when(accountRepository.findByAccountNumberWithLock("100-00000001")).thenReturn(Optional.of(from));
        when(accountRepository.findByAccountNumberWithLock("200-00000002")).thenReturn(Optional.of(to));

        TransferRequest request = new TransferRequest("100-00000001", "200-00000002", new BigDecimal("100.00"));

        assertThatThrownBy(() -> transferService.transfer(request, 1L))
                .isInstanceOf(AccountNotActiveException.class);
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    @Test
    void transfer_notOwner_throwsUnauthorizedAccessException() {
        User owner = createUser(2L);
        User other = createUser(3L);
        Account from = createAccount("100-00000001", owner, new BigDecimal("1000.00"));
        Account to = createAccount("200-00000002", other, new BigDecimal("500.00"));

        when(accountRepository.findByAccountNumberWithLock("100-00000001")).thenReturn(Optional.of(from));
        when(accountRepository.findByAccountNumberWithLock("200-00000002")).thenReturn(Optional.of(to));

        TransferRequest request = new TransferRequest("100-00000001", "200-00000002", new BigDecimal("300.00"));

        assertThatThrownBy(() -> transferService.transfer(request, 1L))
                .isInstanceOf(UnauthorizedAccessException.class);
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    private User createUser(Long id) {
        User user = User.builder()
                .email("user" + id + "@bank.com")
                .password("pw")
                .name("user" + id)
                .build();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private Account createAccount(String accountNumber, User user, BigDecimal initialBalance) {
        Account account = Account.builder()
                .accountNumber(accountNumber)
                .ownerName("owner")
                .user(user)
                .build();
        account.deposit(initialBalance);
        return account;
    }
}


