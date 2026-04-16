package com.identicum.connectors;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KohaConfigurationValidationTest {

    @Test
    void testValidBasicConfig() {
        KohaConfiguration config = new KohaConfiguration();
        config.setServiceAddress("http://koha.example.com");
        config.setAuthenticationMethodStrategy("BASIC");
        config.setUsername("admin");
        config.setPassword(new org.identityconnectors.common.security.GuardedString("secret".toCharArray()));
        assertDoesNotThrow(config::validate);
    }

    @Test
    void testBasicWithNullPassword() {
        KohaConfiguration config = new KohaConfiguration();
        config.setServiceAddress("http://koha.example.com");
        config.setAuthenticationMethodStrategy("BASIC");
        config.setUsername("admin");
        config.setPassword(null);
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    void testBasicWithEmptyUsername() {
        KohaConfiguration config = new KohaConfiguration();
        config.setServiceAddress("http://koha.example.com");
        config.setAuthenticationMethodStrategy("BASIC");
        config.setUsername("");
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    void testValidOAuth2Config() {
        KohaConfiguration config = new KohaConfiguration();
        config.setServiceAddress("http://koha.example.com");
        config.setAuthenticationMethodStrategy("OAUTH2");
        config.setClientId("client-id");
        config.setClientSecret(new org.identityconnectors.common.security.GuardedString("secret".toCharArray()));
        assertDoesNotThrow(config::validate);
    }

    @Test
    void testOAuth2WithEmptyClientId() {
        KohaConfiguration config = new KohaConfiguration();
        config.setServiceAddress("http://koha.example.com");
        config.setAuthenticationMethodStrategy("OAUTH2");
        config.setClientId("");
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    void testInvalidAuthMethod() {
        KohaConfiguration config = new KohaConfiguration();
        config.setServiceAddress("http://koha.example.com");
        config.setAuthenticationMethodStrategy("INVALID");
        assertThrows(IllegalArgumentException.class, config::validate);
    }
}
