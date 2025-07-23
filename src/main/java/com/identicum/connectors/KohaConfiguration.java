package com.identicum.connectors;

import org.identityconnectors.framework.spi.ConfigurationProperty;
import java.util.Arrays;
import java.util.List;

/**
 * Configuración autocontenida y mínima para el conector Koha.
 */
public class KohaConfiguration {

    // === 1. Configuración Base de la API ===
    private String serviceAddress;
    private boolean trustAllCertificates;

    @ConfigurationProperty(order = 10,
            displayMessageKey = "serviceAddress.display",
            helpMessageKey = "serviceAddress.help")
    public String getServiceAddress() {
        return serviceAddress;
    }

    public void setServiceAddress(String serviceAddress) {
        this.serviceAddress = serviceAddress;
    }

    @ConfigurationProperty(order = 11,
            displayMessageKey = "rest.config.trustAllCertificates.display",
            helpMessageKey = "rest.config.trustAllCertificates.help")
    public boolean isTrustAllCertificates() {
        return trustAllCertificates;
    }

    public void setTrustAllCertificates(boolean trustAllCertificates) {
        this.trustAllCertificates = trustAllCertificates;
    }

    // === 2. Autenticación general ===
    private String authenticationMethodStrategy;

    @ConfigurationProperty(order = 15,
            displayMessageKey = "authenticationMethodStrategy.display",
            helpMessageKey = "authenticationMethodStrategy.help")
    public String getAuthenticationMethodStrategy() {
        return authenticationMethodStrategy;
    }

    public void setAuthenticationMethodStrategy(String authenticationMethodStrategy) {
        this.authenticationMethodStrategy = authenticationMethodStrategy;
    }

    // === 3. Autenticación BASIC ===
    private String username;
    private String password;

    @ConfigurationProperty(order = 20,
            displayMessageKey = "username.display",
            helpMessageKey = "username.help")
    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    @ConfigurationProperty(order = 21, confidential = true,
            displayMessageKey = "password.display",
            helpMessageKey = "password.help")
    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    // === 4. Autenticación OAuth2 (Client Credentials) ===
    private String clientId;
    private String clientSecret;

    @ConfigurationProperty(order = 30,
            displayMessageKey = "koha.config.clientId.display",
            helpMessageKey = "koha.config.clientId.help")
    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    @ConfigurationProperty(order = 31, confidential = true,
            displayMessageKey = "koha.config.clientSecret.display",
            helpMessageKey = "koha.config.clientSecret.help")
    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    // === 5. Validación de la configuración ===
    public void validate() {
        if (serviceAddress == null || serviceAddress.trim().isEmpty()) {
            throw new IllegalArgumentException("La dirección del servicio (serviceAddress) no puede estar vacía.");
        }
        if (authenticationMethodStrategy == null || authenticationMethodStrategy.trim().isEmpty()) {
            throw new IllegalArgumentException("La estrategia de autenticación (authenticationMethodStrategy) es obligatoria.");
        }
        // Puedes agregar más validaciones si lo necesitas
    }

    /**
     * Devuelve sólo los nombres de las propiedades locales, útil para la UI de MidPoint.
     */
    public static List<String> getLocalConfigurationProperties() {
        return Arrays.asList(
            "serviceAddress",
            "trustAllCertificates",
            "authenticationMethodStrategy",
            "username",
            "password",
            "clientId",
            "clientSecret"
        );
    }
}
