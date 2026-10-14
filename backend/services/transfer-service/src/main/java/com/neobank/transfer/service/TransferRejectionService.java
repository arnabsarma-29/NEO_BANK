package com.neobank.transfer.service;

import com.neobank.transfer.domain.NextAction;
import com.neobank.transfer.domain.TransferStatus;
import com.neobank.transfer.domain.TransferWorkflow;
import com.neobank.transfer.exception.TransferNotFoundException;
import com.neobank.transfer.repository.TransferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Manages autonomous terminal rejection transitions for transfers.
 * Uses REQUIRES_NEW propagation to ensure terminal rejection is persisted immediately
 * and independently of calling transaction rollbacks (e.g. when runtime exceptions
 * bubble up to produce HTTP 429 responses).
 */
@Service
public class TransferRejectionService {

    private static final Logger log = LoggerFactory.getLogger(TransferRejectionService.class);

    private final TransferRepository transferRepository;

    public TransferRejectionService(TransferRepository transferRepository) {
        this.transferRepository = transferRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TransferWorkflow rejectTransfer(String transferId, String customerId, String reasonCode) {
        TransferWorkflow workflow = transferRepository.findByTransferIdAndCustomerId(transferId, customerId)
                .orElseThrow(() -> new TransferNotFoundException("Transfer not found"));

        log.info("Transitioning transfer {} to REJECTED with reasonCode={}", transferId, reasonCode);
        workflow.setStatus(TransferStatus.REJECTED);
        workflow.setNextAction(NextAction.NONE);
        workflow.setStatusReasonCode(reasonCode);
        workflow.setUpdatedAt(Instant.now());
        return transferRepository.saveAndFlush(workflow);
    }
}
