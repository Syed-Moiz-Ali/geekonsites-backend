package com.geekonsites.backend.dto;

import com.geekonsites.backend.entity.User;

/** PHASE 9 — safe, paginated admin customer projection (no password/credential fields). */
public record AdminCustomerResponse(
        Long id,
        String fullName,
        String email,
        String phone,
        String country,
        String role
) {
    public static AdminCustomerResponse from(User user) {
        return new AdminCustomerResponse(
                user.getId(), user.getFullName(), user.getEmail(), user.getPhone(),
                user.getCountry(), user.getRole() == null ? null : user.getRole().name());
    }
}
