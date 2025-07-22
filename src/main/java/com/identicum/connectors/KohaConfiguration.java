package com.identicum.connectors;

import com.evolveum.polygon.rest.AbstractRestConfiguration;
import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.spi.ConfigurationProperty;

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

    // === 4.b Propiedades heredadas sin exponer en UI ===

    @Override
    public String getProxy() {
        return super.getProxy();
    }

    @Override
    public void setProxy(String proxy) {
        super.setProxy(proxy);
    }

    @Override
    public Integer getProxyPort() {
        return super.getProxyPort();
    }

    @Override
    public void setProxyPort(Integer proxyPort) {
        super.setProxyPort(proxyPort);
    }

    @Override
    public String getTokenName() {
        return super.getTokenName();
    }

    @Override
    public void setTokenName(String tokenName) {
        super.setTokenName(tokenName);
    }

    @Override
    public GuardedString getTokenValue() {
        return super.getTokenValue();
    }

    @Override
    public void setTokenValue(GuardedString tokenValue) {
        super.setTokenValue(tokenValue);
    }

    @Override
    public String getAuthMethod() {
        return super.getAuthMethod();
    }

    @Override
    public void setAuthMethod(String authMethod) {
        super.setAuthMethod(authMethod);
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
