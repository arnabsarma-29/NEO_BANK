package com.neobank.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neobank.schedule.domain.OccurrenceStatus;
import com.neobank.schedule.domain.RecurrenceFrequency;
import com.neobank.schedule.domain.ScheduleStatus;
import com.neobank.schedule.domain.ScheduleType;
import com.neobank.schedule.dto.CreateScheduleRequest;
import com.neobank.schedule.dto.OccurrenceResponse;
import com.neobank.schedule.dto.ScheduleResponse;
import com.neobank.schedule.exception.InvalidScheduleStateException;
import com.neobank.schedule.exception.ScheduleNotFoundException;
import com.neobank.schedule.exception.UnauthorizedScheduleAccessException;
import com.neobank.schedule.service.ScheduleManagementService;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
public class ScheduleControllerIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @MockitoBean
    private ScheduleManagementService managementService;

    private MockMvc mockMvc;

    private final Instant baseTime = Instant.parse("2026-10-15T12:00:00Z");

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    @DisplayName("POST /api/schedules - Creates schedule successfully (201 Created)")
    void testCreateSchedule_Success() throws Exception {
        CreateScheduleRequest request = new CreateScheduleRequest(
                "ACC-1",
                "ACC-2",
                null,
                "INTERNAL",
                new BigDecimal("100.00"),
                "USD",
                "Rent",
                ScheduleType.ONE_TIME,
                null,
                baseTime,
                null
        );

        ScheduleResponse response = new ScheduleResponse(
                "SCH-001", "C1001", "ACC-1", "ACC-2", null, "INTERNAL",
                new BigDecimal("100.00"), "USD", "Rent", ScheduleType.ONE_TIME, null,
                baseTime, null, baseTime, ScheduleStatus.ACTIVE, 1, baseTime, baseTime
        );

        when(managementService.createSchedule(any(CreateScheduleRequest.class), eq("C1001")))
                .thenReturn(response);

        mockMvc.perform(post("/api/schedules")
                        .header("X-Customer-Id", "C1001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scheduleId").value("SCH-001"))
                .andExpect(jsonPath("$.customerId").value("C1001"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("POST /api/schedules - Request validation failure returns 400 Bad Request")
    void testCreateSchedule_ValidationError() throws Exception {
        // Missing sourceAccountId and invalid zero amount
        CreateScheduleRequest invalidRequest = new CreateScheduleRequest(
                "",
                "ACC-2",
                null,
                "INTERNAL",
                new BigDecimal("0.00"),
                "USD",
                "Invalid",
                ScheduleType.ONE_TIME,
                null,
                baseTime,
                null
        );

        mockMvc.perform(post("/api/schedules")
                        .header("X-Customer-Id", "C1001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("GET /api/schedules - Lists schedules for customer (200 OK)")
    void testListSchedules() throws Exception {
        ScheduleResponse res = new ScheduleResponse(
                "SCH-001", "C1001", "ACC-1", "ACC-2", null, "INTERNAL",
                new BigDecimal("100.00"), "USD", "Rent", ScheduleType.ONE_TIME, null,
                baseTime, null, baseTime, ScheduleStatus.ACTIVE, 1, baseTime, baseTime
        );

        when(managementService.listCustomerSchedules("C1001")).thenReturn(List.of(res));

        mockMvc.perform(get("/api/schedules")
                        .header("X-Customer-Id", "C1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].scheduleId").value("SCH-001"));
    }

    @Test
    @DisplayName("GET /api/schedules/{id} - Returns schedule (200 OK)")
    void testGetSchedule() throws Exception {
        ScheduleResponse res = new ScheduleResponse(
                "SCH-001", "C1001", "ACC-1", "ACC-2", null, "INTERNAL",
                new BigDecimal("100.00"), "USD", "Rent", ScheduleType.ONE_TIME, null,
                baseTime, null, baseTime, ScheduleStatus.ACTIVE, 1, baseTime, baseTime
        );

        when(managementService.getSchedule("SCH-001", "C1001")).thenReturn(res);

        mockMvc.perform(get("/api/schedules/SCH-001")
                        .header("X-Customer-Id", "C1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduleId").value("SCH-001"));
    }

    @Test
    @DisplayName("GET /api/schedules/{id} - Schedule not found returns 404")
    void testGetSchedule_NotFound() throws Exception {
        when(managementService.getSchedule("SCH-NONEXISTENT", "C1001"))
                .thenThrow(new ScheduleNotFoundException("Schedule not found: SCH-NONEXISTENT"));

        mockMvc.perform(get("/api/schedules/SCH-NONEXISTENT")
                        .header("X-Customer-Id", "C1001"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SCHEDULE_NOT_FOUND"));
    }

    @Test
    @DisplayName("GET /api/schedules/{id} - Unauthorized customer returns 403 Forbidden")
    void testGetSchedule_Unauthorized() throws Exception {
        when(managementService.getSchedule("SCH-001", "C2002"))
                .thenThrow(new UnauthorizedScheduleAccessException("Unauthorized"));

        mockMvc.perform(get("/api/schedules/SCH-001")
                        .header("X-Customer-Id", "C2002"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED_SCHEDULE_ACCESS"));
    }

    @Test
    @DisplayName("GET /api/schedules/{id}/occurrences - Lists occurrences (200 OK)")
    void testListOccurrences() throws Exception {
        OccurrenceResponse occ = new OccurrenceResponse(
                "OCC-001", "SCH-001", 1, "TRF-SCH-001", baseTime, baseTime,
                OccurrenceStatus.PENDING, null, 0, 3, null, 0L, null, null, null, null, baseTime, baseTime
        );

        when(managementService.listScheduleOccurrences("SCH-001", "C1001")).thenReturn(List.of(occ));

        mockMvc.perform(get("/api/schedules/SCH-001/occurrences")
                        .header("X-Customer-Id", "C1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].occurrenceId").value("OCC-001"));
    }

    @Test
    @DisplayName("POST /api/schedules/{id}/pause - Pauses schedule (200 OK)")
    void testPauseSchedule() throws Exception {
        ScheduleResponse res = new ScheduleResponse(
                "SCH-001", "C1001", "ACC-1", "ACC-2", null, "INTERNAL",
                new BigDecimal("100.00"), "USD", "Rent", ScheduleType.ONE_TIME, null,
                baseTime, null, null, ScheduleStatus.PAUSED, 1, baseTime, baseTime
        );

        when(managementService.pauseSchedule("SCH-001", "C1001")).thenReturn(res);

        mockMvc.perform(post("/api/schedules/SCH-001/pause")
                        .header("X-Customer-Id", "C1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED"));
    }

    @Test
    @DisplayName("POST /api/schedules/{id}/pause - Conflict on already paused schedule returns 409")
    void testPauseSchedule_Conflict() throws Exception {
        when(managementService.pauseSchedule("SCH-001", "C1001"))
                .thenThrow(new InvalidScheduleStateException("Only ACTIVE schedules can be paused"));

        mockMvc.perform(post("/api/schedules/SCH-001/pause")
                        .header("X-Customer-Id", "C1001"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_SCHEDULE_STATE"));
    }

    @Test
    @DisplayName("POST /api/schedules/{id}/resume - Resumes schedule (200 OK)")
    void testResumeSchedule() throws Exception {
        ScheduleResponse res = new ScheduleResponse(
                "SCH-001", "C1001", "ACC-1", "ACC-2", null, "INTERNAL",
                new BigDecimal("100.00"), "USD", "Rent", ScheduleType.ONE_TIME, null,
                baseTime, null, baseTime, ScheduleStatus.ACTIVE, 1, baseTime, baseTime
        );

        when(managementService.resumeSchedule("SCH-001", "C1001")).thenReturn(res);

        mockMvc.perform(post("/api/schedules/SCH-001/resume")
                        .header("X-Customer-Id", "C1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("POST /api/schedules/{id}/cancel - Cancels schedule (200 OK)")
    void testCancelSchedule() throws Exception {
        ScheduleResponse res = new ScheduleResponse(
                "SCH-001", "C1001", "ACC-1", "ACC-2", null, "INTERNAL",
                new BigDecimal("100.00"), "USD", "Rent", ScheduleType.ONE_TIME, null,
                baseTime, null, null, ScheduleStatus.CANCELLED, 1, baseTime, baseTime
        );

        when(managementService.cancelSchedule("SCH-001", "C1001")).thenReturn(res);

        mockMvc.perform(post("/api/schedules/SCH-001/cancel")
                        .header("X-Customer-Id", "C1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }
}
