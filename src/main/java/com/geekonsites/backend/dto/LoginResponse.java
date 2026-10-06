package com.geekonsites.backend.dto;

import com.geekonsites.backend.enums.Role;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LoginResponse {

    private Long id;
    private String fullName;
    private String email;
    private String phone;
    private String country;
    private Role role;
    private String token;
    private String message;
}
