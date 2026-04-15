// TransactionRepository.java
package com.bank.accountservice.repository;

import com.bank.accountservice.entity.Account;
import com.bank.accountservice.entity.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    Page<Transaction> findByAccountOrderByCreatedAtDesc(Account account, Pageable pageable);
}