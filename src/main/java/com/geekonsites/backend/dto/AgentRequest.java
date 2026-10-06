package com.geekonsites.backend.dto;

import lombok.Data;

@Data
public class AgentRequest {

    private String name;
    private String email;
    private String password;
    private String phone;
    private String country;
    private String city;
}