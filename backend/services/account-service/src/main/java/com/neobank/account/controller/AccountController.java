package com.neobank.account.controller;

import com.neobank.account.dto.AccountDetailResponse;
import com.neobank.account.dto.AccountSummaryResponse;
import com.neobank.account.dto.AccountTransactionResponse;
import com.neobank.account.exception.UnauthorizedException;
import com.neobank.account.security.CustomerContextHolder;
import com.neobank.account.security.CustomerPrincipal;
import com.neobank.account.service.AccountService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    /**
     * Lists all accounts belonging to the authenticated customer.
     * Enforces customer scoping strictly via trusted authentication context (ADR-014).
     */
    @GetMapping
    public ResponseEntity<List<AccountSummaryResponse>> getAccounts() {
        String customerId = getAuthenticatedCustomerId();
        List<AccountSummaryResponse> accounts = accountService.getAccountsForCustomer(customerId);
        return ResponseEntity.ok(accounts);
    }

    /**
     * Retrieves account detail and projected balance for an authorized account.
     * Returns 404 Not Found if missing or unauthorized to prevent account existence disclosure.
     */
    @GetMapping("/{accountId}")
    public ResponseEntity<AccountDetailResponse> getAccountDetail(@PathVariable("accountId") String accountId) {
        String customerId = getAuthenticatedCustomerId();
        AccountDetailResponse account = accountService.getAccountDetail(accountId, customerId);
        return ResponseEntity.ok(account);
    }

    /**
     * Retrieves transaction history read model for an authorized account.
     * Returns 404 Not Found if missing or unauthorized.
     */
    @GetMapping("/{accountId}/transactions")
    public ResponseEntity<List<AccountTransactionResponse>> getAccountTransactions(
            @PathVariable("accountId") String accountId) {
        String customerId = getAuthenticatedCustomerId();
        List<AccountTransactionResponse> transactions = accountService.getAccountTransactions(accountId, customerId);
        return ResponseEntity.ok(transactions);
    }

    private String getAuthenticatedCustomerId() {
        return CustomerContextHolder.getCustomer()
                .map(CustomerPrincipal::customerId)
                .orElseThrow(() -> new UnauthorizedException("Authenticated customer context is required"));
    }
}
