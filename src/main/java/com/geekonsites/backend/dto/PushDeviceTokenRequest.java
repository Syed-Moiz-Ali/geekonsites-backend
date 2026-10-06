package com.geekonsites.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PushDeviceTokenRequest {
    @NotBlank
    @Size(max = 2048)
    private String token;

    @NotBlank
    @Pattern(regexp = "^(android|ios)$")
    private String platform;
}
