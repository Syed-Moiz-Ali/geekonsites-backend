package com.geekonsites.backend.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CustomerController {

    @GetMapping("/api/customer/profile")
    public String profile() {
        return "Welcome to GeekOnSites Customer Dashboard";
    }
}