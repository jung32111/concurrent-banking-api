// TransactionRepository.java
package com.bank.repository;

import com.bank.entity.Account;
import com.bank.entity.Transaction;
import com.bank.entity.TransactionType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    Slice<Transaction> findByAccountOrderByIdDesc(Account account, Pageable pageable);
    Slice<Transaction> findByAccountAndIdLessThanOrderByIdDesc(Account account, Long cursor, Pageable pageable);

    /** 특정 계좌의 지정 타입 거래 금액을 기간 내에서 합산. 없으면 0. */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t " +
            "WHERE t.account = :account AND t.type IN :types " +
            "AND t.createdAt >= :from AND t.createdAt < :to")
    BigDecimal sumAmountByAccountAndTypesAndCreatedAtBetween(
            @Param("account") Account account,
            @Param("types") Collection<TransactionType> types,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);
}
