package com.neobank.schedule.controller;

import com.neobank.schedule.dto.CreateScheduleRequest;
import com.neobank.schedule.dto.OccurrenceResponse;
import com.neobank.schedule.dto.ScheduleResponse;
import com.neobank.schedule.security.CustomerPrincipal;
import com.neobank.schedule.security.SecurityContextUtil;
import com.neobank.schedule.service.ScheduleManagementService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {

    private final ScheduleManagementService scheduleManagementService;

    public ScheduleController(ScheduleManagementService scheduleManagementService) {
        this.scheduleManagementService = scheduleManagementService;
    }

    private String resolveCustomerId(String headerCustomerId) {
        CustomerPrincipal principal = SecurityContextUtil.getPrincipal();
        if (principal != null && principal.getCustomerId() != null) {
            return principal.getCustomerId();
        }
        if (headerCustomerId != null && !headerCustomerId.isBlank()) {
            return headerCustomerId;
        }
        return "C1001"; // Baseline test/simulation identity
    }

    @PostMapping
    public ResponseEntity<ScheduleResponse> createSchedule(
            @Valid @RequestBody CreateScheduleRequest request,
            @RequestHeader(value = "X-Customer-Id", required = false) String headerCustomerId) {
        String customerId = resolveCustomerId(headerCustomerId);
        ScheduleResponse response = scheduleManagementService.createSchedule(request, customerId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<ScheduleResponse>> listSchedules(
            @RequestHeader(value = "X-Customer-Id", required = false) String headerCustomerId) {
        String customerId = resolveCustomerId(headerCustomerId);
        List<ScheduleResponse> responses = scheduleManagementService.listCustomerSchedules(customerId);
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/{scheduleId}")
    public ResponseEntity<ScheduleResponse> getSchedule(
            @PathVariable String scheduleId,
            @RequestHeader(value = "X-Customer-Id", required = false) String headerCustomerId) {
        String customerId = resolveCustomerId(headerCustomerId);
        ScheduleResponse response = scheduleManagementService.getSchedule(scheduleId, customerId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{scheduleId}/occurrences")
    public ResponseEntity<List<OccurrenceResponse>> listOccurrences(
            @PathVariable String scheduleId,
            @RequestHeader(value = "X-Customer-Id", required = false) String headerCustomerId) {
        String customerId = resolveCustomerId(headerCustomerId);
        List<OccurrenceResponse> responses = scheduleManagementService.listScheduleOccurrences(scheduleId, customerId);
        return ResponseEntity.ok(responses);
    }

    @PostMapping("/{scheduleId}/pause")
    public ResponseEntity<ScheduleResponse> pauseSchedule(
            @PathVariable String scheduleId,
            @RequestHeader(value = "X-Customer-Id", required = false) String headerCustomerId) {
        String customerId = resolveCustomerId(headerCustomerId);
        ScheduleResponse response = scheduleManagementService.pauseSchedule(scheduleId, customerId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{scheduleId}/resume")
    public ResponseEntity<ScheduleResponse> resumeSchedule(
            @PathVariable String scheduleId,
            @RequestHeader(value = "X-Customer-Id", required = false) String headerCustomerId) {
        String customerId = resolveCustomerId(headerCustomerId);
        ScheduleResponse response = scheduleManagementService.resumeSchedule(scheduleId, customerId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{scheduleId}/cancel")
    public ResponseEntity<ScheduleResponse> cancelSchedule(
            @PathVariable String scheduleId,
            @RequestHeader(value = "X-Customer-Id", required = false) String headerCustomerId) {
        String customerId = resolveCustomerId(headerCustomerId);
        ScheduleResponse response = scheduleManagementService.cancelSchedule(scheduleId, customerId);
        return ResponseEntity.ok(response);
    }
}
