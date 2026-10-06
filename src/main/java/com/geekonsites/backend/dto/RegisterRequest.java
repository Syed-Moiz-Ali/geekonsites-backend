package com.geekonsites.backend.dto;

import com.geekonsites.backend.enums.Role;
import lombok.Data;
import jakarta.validation.constraints.Pattern;

@Data
public class RegisterRequest {

    private String fullName;
    private String email;
    @Pattern(
            regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{8,15}$",
            message = "Password must contain 8 to 15 characters with uppercase, lowercase, number, and special character"
    )
    private String password;
    private String phone;
    private String country;
    private Role role;
}
