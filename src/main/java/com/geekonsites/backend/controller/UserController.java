package com.geekonsites.backend.controller;

import com.geekonsites.backend.dto.AuthResponse;
import com.geekonsites.backend.entity.User;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
public class UserController {

    @GetMapping("/me")
    public AuthResponse getCurrentUser(Authentication authentication) {

        User user = (User) authentication.getPrincipal();

        return new AuthResponse(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getPhone(),
                user.getCountry(),
                user.getRole(),
                "Profile fetched successfully"
        );
    }
}
