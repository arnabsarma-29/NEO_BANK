package com.neobank.account.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.neobank.account.domain.AccountStatus;
import com.neobank.account.domain.AccountType;
import com.neobank.account.domain.EntryType;
import com.neobank.account.domain.TransferType;
import com.neobank.account.dto.AccountDetailResponse;
import com.neobank.account.dto.AccountSummaryResponse;
import com.neobank.account.dto.AccountTransactionResponse;
import com.neobank.account.exception.AccountNotFoundException;
import com.neobank.account.exception.GlobalExceptionHandler;
import com.neobank.account.security.CustomerContextHolder;
import com.neobank.account.service.AccountService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AccountControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Mock
    private AccountService accountService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AccountController(accountService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        CustomerContextHolder.setCustomer("C1001", "user-1001");
    }

    @AfterEach
    void tearDown() {
        CustomerContextHolder.clear();
    }

    @Test
    @DisplayName("GET /api/accounts returns 200 with customer account list")
    void shouldReturnCustomerAccounts() throws Exception {
        AccountSummaryResponse acc1 = new AccountSummaryResponse(
                "ACC-1001", "1001000101", "XXXXXX0101", AccountType.SAVINGS,
                "INR", new BigDecimal("1000.0000"), AccountStatus.ACTIVE, Instant.now()
        );
        when(accountService.getAccountsForCustomer("C1001")).thenReturn(List.of(acc1));

        mockMvc.perform(get("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].accountId").value("ACC-1001"))
                .andExpect(jsonPath("$[0].maskedAccountNumber").value("XXXXXX0101"))
                .andExpect(jsonPath("$[0].accountType").value("SAVINGS"))
                .andExpect(jsonPath("$[0].currency").value("INR"))
                .andExpect(jsonPath("$[0].balance").value(1000.0000));
    }

    @Test
    @DisplayName("GET /api/accounts returns 401 when no customer authentication context")
    void shouldReturn401WhenUnauthenticated() throws Exception {
        CustomerContextHolder.clear();

        mockMvc.perform(get("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("GET /api/accounts/{accountId} returns 200 with account detail")
    void shouldReturnAccountDetailWhenOwned() throws Exception {
        AccountDetailResponse detail = new AccountDetailResponse(
                "ACC-1001", "1001000101", "XXXXXX0101", "C1001",
                AccountType.SAVINGS, "INR", new BigDecimal("2500.0000"),
                AccountStatus.ACTIVE, 0L, Instant.now(), Instant.now()
        );
        when(accountService.getAccountDetail("ACC-1001", "C1001")).thenReturn(detail);

        mockMvc.perform(get("/api/accounts/ACC-1001")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("ACC-1001"))
                .andExpect(jsonPath("$.customerId").value("C1001"))
                .andExpect(jsonPath("$.balance").value(2500.0000));
    }

    @Test
    @DisplayName("GET /api/accounts/{accountId} returns 404 when not found or owned by another customer")
    void shouldReturn404WhenUnauthorizedOrMissing() throws Exception {
        when(accountService.getAccountDetail("ACC-9999", "C1001"))
                .thenThrow(new AccountNotFoundException("Account not found: ACC-9999", "ACC-9999"));

        mockMvc.perform(get("/api/accounts/ACC-9999")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    @DisplayName("GET /api/accounts/{accountId}/transactions returns 200 with transaction read models")
    void shouldReturnTransactions() throws Exception {
        AccountTransactionResponse tx = new AccountTransactionResponse(
                "ATX-1", "ACC-1001", "TX-1", "TRF-1", EntryType.DEBIT,
                new BigDecimal("500.0000"), "INR", TransferType.OWN_ACCOUNT,
                "REF-1", "Remarks", "Destination", "XXXXXX1002", Instant.now()
        );
        when(accountService.getAccountTransactions("ACC-1001", "C1001")).thenReturn(List.of(tx));

        mockMvc.perform(get("/api/accounts/ACC-1001/transactions")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("ATX-1"))
                .andExpect(jsonPath("$[0].entryType").value("DEBIT"))
                .andExpect(jsonPath("$[0].amount").value(500.0000));
    }
}
