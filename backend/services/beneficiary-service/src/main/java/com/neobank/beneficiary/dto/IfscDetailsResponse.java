package com.neobank.beneficiary.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IfscDetailsResponse {

    private String ifscCode;
    private String bankName;
    private String branchName;
    private String address;
    private String city;
    private String state;
    private String status;
}
