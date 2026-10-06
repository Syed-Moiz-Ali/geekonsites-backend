package com.geekonsites.backend.dto;

import lombok.Data;

@Data
public class CustomerLocationRequest {

    private Double latitude;
    private Double longitude;
}