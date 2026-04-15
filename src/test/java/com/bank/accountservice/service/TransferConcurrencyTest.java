package com.bank.accountservice.service;

import com.bank.accountservice.dto.TransferRequest;
import com.bank.accountservice.entity.Account;
import com.bank.accountservice.entity.User;
import com.bank.accountservice.repository.AccountRepository;
import com.bank.accountservice.repository.TransactionRepository;
import com.bank.accountservice.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class TransferConcurrencyTest {

    @Autowired
    private TransferService transferService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void concurrentBidirectionalTransfer_finishesWithin5Seconds_withoutDeadlock() throws InterruptedException {
        User user = userRepository.save(User.builder()
                .email("transfer@test.com")
                .password("pw")
                .name("tester")
                .build());

        Account accountA = Account.builder()
                .accountNumber("100-00000001")
                .ownerName("tester")
                .user(user)
                .build();
        accountA.deposit(new BigDecimal("100000.00"));

        Account accountB = Account.builder()
                .accountNumber("100-00000002")
                .ownerName("tester")
                .user(user)
                .build();
        accountB.deposit(new BigDecimal("100000.00"));

        accountRepository.save(accountA);
        accountRepository.save(accountB);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        AtomicReference<Throwable> error = new AtomicReference<>();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    transferService.transfer(
                            new TransferRequest("100-00000001", "100-00000002", new BigDecimal("1000.00")),
                            user.getId());
                } catch (Throwable t) {
                    error.compareAndSet(null, t);
                } finally {
                    doneLatch.countDown();
                }
            });

            executor.submit(() -> {
                try {
                    startLatch.await();
                    transferService.transfer(
                            new TransferRequest("100-00000002", "100-00000001", new BigDecimal("1000.00")),
                            user.getId());
                } catch (Throwable t) {
                    error.compareAndSet(null, t);
                } finally {
                    doneLatch.countDown();
                }
            });

            startLatch.countDown();
            boolean finished = doneLatch.await(5, TimeUnit.SECONDS);

            assertThat(finished).isTrue();
            assertThat(error.get()).isNull();
        } finally {
            executor.shutdownNow();
        }

        Account refreshedA = accountRepository.findByAccountNumber("100-00000001").orElseThrow();
        Account refreshedB = accountRepository.findByAccountNumber("100-00000002").orElseThrow();

        assertThat(refreshedA.getBalance()).isEqualByComparingTo("100000.00");
        assertThat(refreshedB.getBalance()).isEqualByComparingTo("100000.00");
    }
}


