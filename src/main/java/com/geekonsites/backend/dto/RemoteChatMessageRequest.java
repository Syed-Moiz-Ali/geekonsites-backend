package com.geekonsites.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RemoteChatMessageRequest {
    @NotBlank
    @Size(max = 2000)
    private String message;
}
