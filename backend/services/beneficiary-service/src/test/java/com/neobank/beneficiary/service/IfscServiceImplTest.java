package com.neobank.beneficiary.service;

import com.neobank.beneficiary.dto.IfscDetailsResponse;
import com.neobank.beneficiary.dto.IfscRegistrationRequest;
import com.neobank.beneficiary.entity.IfscDirectory;
import com.neobank.beneficiary.exception.ApiException;
import com.neobank.beneficiary.mapper.BeneficiaryMapper;
import com.neobank.beneficiary.repository.IfscDirectoryRepository;
import com.neobank.beneficiary.service.impl.IfscServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IfscServiceImplTest {

    @Mock
    private IfscDirectoryRepository ifscDirectoryRepository;

    private BeneficiaryMapper beneficiaryMapper;
    private IfscServiceImpl ifscService;

    @BeforeEach
    void setUp() {
        beneficiaryMapper = new BeneficiaryMapper();
        ifscService = new IfscServiceImpl(ifscDirectoryRepository, beneficiaryMapper);
    }

    @Test
    @DisplayName("validateAndGet succeeds for registered IFSC in directory")
    void validateAndGetSucceedsForRegisteredIfsc() {
        IfscDirectory directory = new IfscDirectory();
        directory.setId(1L);
        directory.setIfscCode("SBIN0001234");
        directory.setBankName("State Bank of India");
        directory.setBranchName("Connaught Place");
        directory.setStatus("ACTIVE");

        when(ifscDirectoryRepository.findByIfscCodeIgnoreCase("SBIN0001234"))
            .thenReturn(Optional.of(directory));

        IfscDirectory result = ifscService.validateAndGet("SBIN0001234");

        assertThat(result).isNotNull();
        assertThat(result.getIfscCode()).isEqualTo("SBIN0001234");
        assertThat(result.getBankName()).isEqualTo("State Bank of India");
        verify(ifscDirectoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("validateAndGet rejects unknown IFSC and does NOT insert directory record")
    void validateAndGetRejectsUnknownIfscWithoutInserting() {
        when(ifscDirectoryRepository.findByIfscCodeIgnoreCase("HDFC0009999"))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> ifscService.validateAndGet("HDFC0009999"))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> {
                ApiException apiEx = (ApiException) ex;
                assertThat(apiEx.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(apiEx.getBody().getDetail()).contains("IFSC code not found in directory");
            });

        verify(ifscDirectoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("validateAndGet rejects invalid IFSC code format")
    void validateAndGetRejectsInvalidFormat() {
        assertThatThrownBy(() -> ifscService.validateAndGet("invalid_ifsc"))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> {
                ApiException apiEx = (ApiException) ex;
                assertThat(apiEx.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(apiEx.getBody().getDetail()).contains("Invalid IFSC code format");
            });

        verify(ifscDirectoryRepository, never()).findByIfscCodeIgnoreCase(any());
    }

    @Test
    @DisplayName("registerIfsc creates a new directory record")
    void registerIfscCreatesRecord() {
        IfscRegistrationRequest request = IfscRegistrationRequest.builder()
            .ifscCode("ICIC0001111")
            .bankName("ICICI Bank")
            .branchName("Nariman Point")
            .city("Mumbai")
            .state("Maharashtra")
            .build();

        when(ifscDirectoryRepository.findByIfscCodeIgnoreCase("ICIC0001111"))
            .thenReturn(Optional.empty());

        IfscDirectory saved = new IfscDirectory();
        saved.setId(10L);
        saved.setIfscCode("ICIC0001111");
        saved.setBankName("ICICI Bank");
        saved.setBranchName("Nariman Point");
        saved.setCity("Mumbai");
        saved.setState("Maharashtra");
        saved.setStatus("ACTIVE");

        when(ifscDirectoryRepository.save(any(IfscDirectory.class))).thenReturn(saved);

        IfscDetailsResponse response = ifscService.registerIfsc(request);

        assertThat(response).isNotNull();
        assertThat(response.getIfscCode()).isEqualTo("ICIC0001111");
        assertThat(response.getBankName()).isEqualTo("ICICI Bank");
        verify(ifscDirectoryRepository).save(any(IfscDirectory.class));
    }

    @Test
    @DisplayName("searchIfsc returns matching IFSC entries")
    void searchIfscReturnsMatches() {
        IfscDirectory d1 = new IfscDirectory();
        d1.setIfscCode("SBIN0001234");
        d1.setBankName("State Bank of India");

        when(ifscDirectoryRepository.searchByQuery("sbin")).thenReturn(List.of(d1));

        List<IfscDetailsResponse> results = ifscService.searchIfsc("sbin");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getIfscCode()).isEqualTo("SBIN0001234");
    }
}
