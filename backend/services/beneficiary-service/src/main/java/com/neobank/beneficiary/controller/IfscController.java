package com.neobank.beneficiary.controller;

import com.neobank.beneficiary.dto.IfscDetailsResponse;
import com.neobank.beneficiary.dto.IfscRegistrationRequest;
import com.neobank.beneficiary.dto.Responses;
import com.neobank.beneficiary.mapper.ApiResponse;
import com.neobank.beneficiary.service.IfscService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/beneficiaries/ifsc")
@RequiredArgsConstructor
@Slf4j
public class IfscController {

    private final IfscService ifscService;

    @PostMapping
 //   @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<IfscDetailsResponse>> registerIfsc(
        @Valid @RequestBody IfscRegistrationRequest request
    ) {
        IfscDetailsResponse details = ifscService.registerIfsc(request);
        URI location = URI.create("/api/v1/beneficiaries/ifsc/" + details.getIfscCode());
        return Responses.created(location, "IFSC details successfully registered.", details);
    }

    @GetMapping("/getAllIfsc")
    public ResponseEntity<ApiResponse<List<IfscDetailsResponse>>> getAllIfsc() {
        List<IfscDetailsResponse> list = ifscService.getAllIfsc();
        return Responses.ok("Registered IFSC entries retrieved successfully.", list);
    }

    @GetMapping("/search")
    public ResponseEntity<ApiResponse<List<IfscDetailsResponse>>> searchIfsc(@RequestParam String query) {
        List<IfscDetailsResponse> results = ifscService.searchIfsc(query);
        return Responses.ok("IFSC search completed.", results);
    }

    @GetMapping("/{ifscCode}")
    public ResponseEntity<ApiResponse<IfscDetailsResponse>> getIfscDetails(@PathVariable String ifscCode) {
        IfscDetailsResponse details = ifscService.getIfscDetails(ifscCode);
        return Responses.ok("IFSC branch details retrieved successfully.", details);
    }
}
