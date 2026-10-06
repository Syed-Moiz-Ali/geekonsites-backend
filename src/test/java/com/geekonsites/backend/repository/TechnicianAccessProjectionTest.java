package com.geekonsites.backend.repository;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class TechnicianAccessProjectionTest {

    @Test
    void loginProjectionQuerySelectsNoVerificationLobColumns() throws Exception {
        Query query = TechnicianRepository.class
                .getMethod("findAccessByEmail", String.class)
                .getAnnotation(Query.class);
        String statement = query.value().toLowerCase(Locale.ROOT);

        assertTrue(statement.contains("verificationstatus"));
        assertTrue(statement.contains("personalemail"));
        assertTrue(statement.contains("companyemail"));
        assertTrue(statement.contains("onboardingstatus"));
        assertTrue(statement.contains("availabilitystatus"));
        assertTrue(statement.contains("servicemode"));
        assertFalse(statement.contains("identitydocumentdata"));
        assertFalse(statement.contains("livephotodata"));
        assertFalse(statement.contains("workauthorizationdocumentdata"));
        assertFalse(statement.contains("addressproofdata"));
        assertFalse(statement.contains("drivinglicensedata"));
        assertFalse(statement.contains("vehicleinsurancedata"));
        assertFalse(statement.contains("publicliabilitydata"));
    }
}
