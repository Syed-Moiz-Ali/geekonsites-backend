package com.geekonsites.backend;

import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class GeekOnSitesApplication {

    public static void main(String[] args) {
        SpringApplication.run(GeekOnSitesApplication.class, args);
    }

    
}
