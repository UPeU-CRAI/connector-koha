package com.identicum.connectors;

import com.evolveum.polygon.rest.AbstractRestConfiguration;
import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.spi.ConfigurationProperty;

import java.util.Arrays;
import java.util.List;

/**
 * Configuración del conector Koha para MidPoint.
 * Esta clase define únicamente las propiedades necesarias para
 * el conector, organizadas en grupos lógicos para la UI.
 */
public class KohaConfiguration extends AbstractRestConfiguration {

    // === 1. Configuración Base de la API ===
    private String serviceAddress;
    private Boolean trustAllCertificates;

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
    public Boolean getTrustAllCertificates() {
        return trustAllCertificates;
    }

    public void setTrustAllCertificates(Boolean trustAllCertificates) {
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
    private GuardedString password;

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
    public GuardedString getPassword() {
        return password;
    }

    public void setPassword(GuardedString password) {
        this.password = password;
    }

    // === 4. Autenticación OAuth2 (Client Credentials) ===
    private String clientId;
    private GuardedString clientSecret;

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
    public GuardedString getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(GuardedString clientSecret) {
        this.clientSecret = clientSecret;
    }

    /**
     * Lista de propiedades locales expuestas por este configuration.
     * Puede usarse desde el conector para evitar exponer los campos
     * heredados de {@link AbstractRestConfiguration}.
     */
    public static List<String> getLocalConfigurationPropertyNames() {
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

    // =====================================================================
    // Métodos heredados anulados para ocultar propiedades no deseadas
    // =====================================================================

    @Override
    public String getAuthMethod() {
        return null; // Oculto en la UI
    }

    @Override
    public void setAuthMethod(String authMethod) {
        // Sin implementación para ocultar esta propiedad
    }

    @Override
    public String getTokenName() {
        return null;
    }

    @Override
    public void setTokenName(String tokenName) {
        // No exponer esta propiedad
    }

    @Override
    public GuardedString getTokenValue() {
        return null;
    }

    @Override
    public void setTokenValue(GuardedString tokenValue) {
        // Propiedad no usada
    }

    @Override
    public String getProxy() {
        return null;
    }

    @Override
    public void setProxy(String proxy) {
        // Propiedad no utilizada
    }

    @Override
    public Integer getProxyPort() {
        return null;
    }

    @Override
    public void setProxyPort(Integer proxyPort) {
        // Propiedad no utilizada
    }

    // === 5. Validación de la configuración ===
    @Override
    public void validate() {
        if (serviceAddress == null || serviceAddress.trim().isEmpty()) {
            throw new IllegalArgumentException("La dirección del servicio (serviceAddress) no puede estar vacía.");
        }
        if (authenticationMethodStrategy == null || authenticationMethodStrategy.trim().isEmpty()) {
            throw new IllegalArgumentException("La estrategia de autenticación (authenticationMethodStrategy) es obligatoria.");
        }
        // Puedes agregar más validaciones si lo necesitas
    }
}
