package com.geekonsites.backend.dto;

import lombok.Data;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Data
public class RegisterRequest {

    @NotBlank(message = "Full name is required")
    private String fullName;

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email")
    private String email;

    @NotBlank(message = "Password is required")
    @Pattern(
            regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{8,15}$",
            message = "Password must contain 8 to 15 characters with uppercase, lowercase, number, and special character"
    )
    private String password;
    private String phone;
    private String country;
    // PHASE 5: the public role field was removed. Public registration always creates a
    // CUSTOMER; caller-supplied role JSON is ignored by Jackson and can never grant privilege.
}
