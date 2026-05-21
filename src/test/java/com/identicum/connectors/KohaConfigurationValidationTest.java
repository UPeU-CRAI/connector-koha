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

    // --- Validacion del canal JDBC ---

    private static KohaConfiguration baseOAuth2Config() {
        KohaConfiguration config = new KohaConfiguration();
        config.setServiceAddress("http://koha.example.com");
        config.setAuthenticationMethodStrategy("OAUTH2");
        config.setClientId("client-id");
        config.setClientSecret(new org.identityconnectors.common.security.GuardedString("secret".toCharArray()));
        return config;
    }

    @Test
    void testJdbcDisabledIgnoresDbFields() {
        // dbEnabled=false: no se validan los campos de BD aunque esten vacios.
        KohaConfiguration config = baseOAuth2Config();
        config.setDbEnabled(false);
        assertDoesNotThrow(config::validate);
    }

    @Test
    void testJdbcEnabledValidConfig() {
        KohaConfiguration config = baseOAuth2Config();
        config.setDbEnabled(true);
        config.setDbHost("192.168.12.130");
        config.setDbName("koha_bul");
        config.setDbUser("ticrai");
        config.setDbPassword(new org.identityconnectors.common.security.GuardedString("dbsecret".toCharArray()));
        assertDoesNotThrow(config::validate);
    }

    @Test
    void testJdbcEnabledMissingHost() {
        KohaConfiguration config = baseOAuth2Config();
        config.setDbEnabled(true);
        config.setDbName("koha_bul");
        config.setDbUser("ticrai");
        config.setDbPassword(new org.identityconnectors.common.security.GuardedString("dbsecret".toCharArray()));
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    void testJdbcEnabledMissingName() {
        KohaConfiguration config = baseOAuth2Config();
        config.setDbEnabled(true);
        config.setDbHost("192.168.12.130");
        config.setDbUser("ticrai");
        config.setDbPassword(new org.identityconnectors.common.security.GuardedString("dbsecret".toCharArray()));
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    void testJdbcEnabledMissingUser() {
        KohaConfiguration config = baseOAuth2Config();
        config.setDbEnabled(true);
        config.setDbHost("192.168.12.130");
        config.setDbName("koha_bul");
        config.setDbPassword(new org.identityconnectors.common.security.GuardedString("dbsecret".toCharArray()));
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    void testJdbcEnabledMissingPassword() {
        KohaConfiguration config = baseOAuth2Config();
        config.setDbEnabled(true);
        config.setDbHost("192.168.12.130");
        config.setDbName("koha_bul");
        config.setDbUser("ticrai");
        config.setDbPassword(null);
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    void testJdbcEnabledInvalidPort() {
        KohaConfiguration config = baseOAuth2Config();
        config.setDbEnabled(true);
        config.setDbHost("192.168.12.130");
        config.setDbName("koha_bul");
        config.setDbUser("ticrai");
        config.setDbPassword(new org.identityconnectors.common.security.GuardedString("dbsecret".toCharArray()));
        config.setDbPort(99999);
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    void testJdbcEnabledInvalidPoolSize() {
        KohaConfiguration config = baseOAuth2Config();
        config.setDbEnabled(true);
        config.setDbHost("192.168.12.130");
        config.setDbName("koha_bul");
        config.setDbUser("ticrai");
        config.setDbPassword(new org.identityconnectors.common.security.GuardedString("dbsecret".toCharArray()));
        config.setDbPoolSize(0);
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    void testJdbcDefaults() {
        KohaConfiguration config = new KohaConfiguration();
        assertFalse(config.getDbEnabled(), "dbEnabled debe ser false por defecto");
        assertEquals(3306, config.getDbPort(), "dbPort debe ser 3306 por defecto");
        assertEquals(2, config.getDbPoolSize(), "dbPoolSize debe ser 2 por defecto");
    }
}
