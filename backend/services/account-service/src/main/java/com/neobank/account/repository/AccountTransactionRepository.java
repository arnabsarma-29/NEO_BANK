package com.neobank.account.repository;

import com.neobank.account.domain.AccountTransactionReadModel;
import com.neobank.account.domain.EntryType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AccountTransactionRepository extends JpaRepository<AccountTransactionReadModel, String> {

    @Query("SELECT tx FROM AccountTransactionReadModel tx WHERE tx.account.id = :accountId ORDER BY tx.transactionTimestamp DESC")
    List<AccountTransactionReadModel> findByAccountIdOrderByTimestampDesc(@Param("accountId") String accountId);

    @Query("SELECT CASE WHEN COUNT(tx) > 0 THEN true ELSE false END FROM AccountTransactionReadModel tx " +
           "WHERE tx.transactionId = :transactionId AND tx.account.id = :accountId AND tx.entryType = :entryType")
    boolean existsByTransactionIdAndAccountIdAndEntryType(
            @Param("transactionId") String transactionId,
            @Param("accountId") String accountId,
            @Param("entryType") EntryType entryType);
}
