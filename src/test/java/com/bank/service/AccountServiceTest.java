package com.bank.service;

import com.bank.dto.AccountCreateRequest;
import com.bank.dto.AccountResponse;
import com.bank.entity.Account;
import com.bank.entity.User;
import com.bank.exception.AccountNotFoundException;
import com.bank.exception.AccountNumberGenerationException;
import com.bank.exception.UnauthorizedAccessException;
import com.bank.exception.UserNotFoundException;
import com.bank.repository.AccountRepository;
import com.bank.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock private AccountRepository accountRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private AccountInserter accountInserter;

    @InjectMocks private AccountService accountService;

    @Test
    void createAccount_success_savesAndReturnsResponse() {
        User user = createUser(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(accountInserter.tryInsert(any(Account.class))).thenAnswer(i -> i.getArgument(0));

        AccountCreateRequest req = new AccountCreateRequest();
        ReflectionTestUtils.setField(req, "ownerName", "홍길동");

        AccountResponse res = accountService.createAccount(req, 1L);

        assertThat(res.getAccountNumber()).startsWith("100-");
        assertThat(res.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(accountInserter).tryInsert(any(Account.class));
    }

    @Test
    void createAccount_userNotFound_throws() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());
        AccountCreateRequest req = new AccountCreateRequest();
        ReflectionTestUtils.setField(req, "ownerName", "홍길동");

        assertThatThrownBy(() -> accountService.createAccount(req, 99L))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void createAccount_uniqueViolationOnce_retriesAndSucceeds() {
        User user = createUser(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(accountInserter.tryInsert(any(Account.class)))
                .thenThrow(new DataIntegrityViolationException("dup"))
                .thenAnswer(i -> i.getArgument(0));

        AccountCreateRequest req = new AccountCreateRequest();
        ReflectionTestUtils.setField(req, "ownerName", "홍길동");

        AccountResponse res = accountService.createAccount(req, 1L);

        assertThat(res.getAccountNumber()).startsWith("100-");
        verify(accountInserter, times(2)).tryInsert(any(Account.class));
    }

    @Test
    void createAccount_allRetriesFail_throwsAccountNumberGenerationException() {
        User user = createUser(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(accountInserter.tryInsert(any(Account.class)))
                .thenThrow(new DataIntegrityViolationException("dup"));

        AccountCreateRequest req = new AccountCreateRequest();
        ReflectionTestUtils.setField(req, "ownerName", "홍길동");

        assertThatThrownBy(() -> accountService.createAccount(req, 1L))
                .isInstanceOf(AccountNumberGenerationException.class);
        verify(accountInserter, times(10)).tryInsert(any(Account.class));
    }

    @Test
    void getAccount_ownerMatches_returnsAccount() {
        User user = createUser(1L);
        Account account = Account.builder().accountNumber("100-00000001").ownerName("o").user(user).build();
        when(accountRepository.findByAccountNumber("100-00000001")).thenReturn(Optional.of(account));

        AccountResponse res = accountService.getAccount("100-00000001", 1L);

        assertThat(res.getAccountNumber()).isEqualTo("100-00000001");
    }

    @Test
    void getAccount_notOwner_throwsUnauthorized() {
        User user = createUser(1L);
        Account account = Account.builder().accountNumber("100-00000001").ownerName("o").user(user).build();
        when(accountRepository.findByAccountNumber("100-00000001")).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> accountService.getAccount("100-00000001", 2L))
                .isInstanceOf(UnauthorizedAccessException.class);
    }

    @Test
    void getAccount_notFound_throws() {
        when(accountRepository.findByAccountNumber(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> accountService.getAccount("100-99999999", 1L))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void getBalance_success_returnsBalance() {
        User user = createUser(1L);
        Account account = Account.builder().accountNumber("100-00000001").ownerName("o").user(user).build();
        account.deposit(new BigDecimal("5000.00"));
        when(accountRepository.findByAccountNumber("100-00000001")).thenReturn(Optional.of(account));

        BigDecimal balance = accountService.getBalance("100-00000001", 1L);

        assertThat(balance).isEqualByComparingTo("5000.00");
    }

    private User createUser(Long id) {
        User u = User.builder().email("a@b.com").password("pw").name("n").build();
        ReflectionTestUtils.setField(u, "id", id);
        return u;
    }
}
