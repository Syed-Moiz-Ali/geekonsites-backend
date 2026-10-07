package com.geekonsites.backend;

import com.geekonsites.backend.controller.*;
import com.geekonsites.backend.auth.AuthController;
import com.geekonsites.backend.service.UkEarlyServiceConsentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.main.lazy-initialization=false",
        "spring.datasource.url=jdbc:h2:mem:runtimeaudit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.jwt.secret=runtime-audit-only-secret-key-with-at-least-32-characters",
        "firebase.enabled=false",
        "google.calendar.enabled=false",
        "spring.mail.host=localhost",
        "spring.mail.port=2525"
})
class ProductionRuntimeContextTest {
    @Autowired ApplicationContext context;

    @Test void eagerlyCreatesAllRestControllersAndCriticalConsentBean() {
        assertNotNull(context.getBean(UkEarlyServiceConsentService.class));
        Class<?>[] controllers = {
                AuthController.class, BookingController.class, TechnicianController.class,
                PaymentController.class, RefundController.class, AdminRefundController.class,
                AgentCrmController.class, NotificationController.class, PushDeviceController.class,
                RemoteSessionController.class, RemoteChatController.class, AdminController.class,
                AgentController.class, ContactController.class,
                InvoiceController.class, RatingController.class, UserController.class,
                HealthController.class, AdminOperationsController.class
        };
        for (Class<?> controller : controllers) {
            assertNotNull(context.getBean(controller), () -> "Missing runtime bean: " + controller.getSimpleName());
        }
    }

    @Test void lazyInitializationIsDisabledForAudit() {
        assertEquals("false", context.getEnvironment().getProperty("spring.main.lazy-initialization"));
    }
}
