package com.neobank.account.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "account_number", length = 32, nullable = false, unique = true)
    private String accountNumber;

    @Column(name = "customer_id", length = 64, nullable = false)
    private String customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", length = 20, nullable = false)
    private AccountType accountType;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "balance", precision = 19, scale = 4, nullable = false)
    private BigDecimal balance;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private AccountStatus status;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Account() {
    }

    public Account(String id, String accountNumber, String customerId, AccountType accountType,
                   String currency, BigDecimal balance, AccountStatus status, Instant createdAt) {
        this.id = id;
        this.accountNumber = accountNumber;
        this.customerId = customerId;
        this.accountType = accountType;
        this.currency = currency;
        this.balance = balance != null ? balance : BigDecimal.ZERO.setScale(4);
        this.status = status != null ? status : AccountStatus.ACTIVE;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = this.createdAt;
    }

    public String getId() {
        return id;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public String getCustomerId() {
        return customerId;
    }

    public AccountType getAccountType() {
        return accountType;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void updateBalance(BigDecimal newBalance) {
        if (newBalance == null || newBalance.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Account balance cannot be negative or null");
        }
        this.balance = newBalance;
        this.updatedAt = Instant.now();
    }

    public void updateStatus(AccountStatus status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    /**
     * Masks account number for customer display (e.g. 1001000101 -> XXXXXX0101)
     */
    public String getMaskedAccountNumber() {
        if (accountNumber == null || accountNumber.length() <= 4) {
            return accountNumber;
        }
        int visibleDigits = 4;
        int maskedCount = accountNumber.length() - visibleDigits;
        return "X".repeat(maskedCount) + accountNumber.substring(maskedCount);
    }
}
