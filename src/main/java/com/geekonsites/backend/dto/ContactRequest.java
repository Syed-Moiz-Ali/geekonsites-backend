package com.geekonsites.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ContactRequest {

    @NotBlank(message = "Full name is required")
    @Size(max = 120, message = "Full name must be 120 characters or fewer")
    private String fullName;

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email")
    @Size(max = 254, message = "Email must be 254 characters or fewer")
    private String email;

    @Pattern(
            regexp = "^$|^(\\+1\\d{10}|\\+44\\d{10,11})$",
            message = "Use a valid US (+1 followed by 10 digits) or UK (+44 followed by 10 or 11 digits) phone number"
    )
    @Size(max = 20, message = "Phone number must be 20 characters or fewer")
    private String phone;

    @NotBlank(message = "Country is required")
    @Pattern(regexp = "^(US|UK)$", message = "Country must be US or UK")
    private String country;

    @NotBlank(message = "Subject is required")
    @Size(max = 160, message = "Subject must be 160 characters or fewer")
    private String subject;

    @NotBlank(message = "Message is required")
    @Size(min = 10, max = 5000, message = "Message must be between 10 and 5000 characters")
    private String message;
}
