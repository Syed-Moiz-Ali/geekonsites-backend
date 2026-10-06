package com.geekonsites.backend.dto;

import lombok.Data;

@Data
public class NotificationRequest {

    private Long customerId;

    private String title;

    private String message;
}