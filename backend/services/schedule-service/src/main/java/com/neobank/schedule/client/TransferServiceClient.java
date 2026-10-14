package com.neobank.schedule.client;

/**
 * Narrow integration boundary to Transfer Service.
 * Schedule Service must never call Ledger Service directly or mutate balances.
 * Execution path: Schedule Service -> Transfer Service -> Ledger Service.
 */
public interface TransferServiceClient {

    TransferExecutionResponse executeTransfer(TransferExecutionRequest request);

    TransferStatusResponse reconcileTransfer(String transferId);
}
