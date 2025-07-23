package com.identicum.connectors;

import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.common.objects.ConnectorMessages; // <-- IMPORT NECESARIO
import org.identityconnectors.framework.spi.Configuration;
import org.identityconnectors.framework.spi.ConfigurationProperty;

/**
 * Configuración autocontenida y mínima para el conector Koha.
 * Implementa la interfaz Configuration, un requisito del framework ConnId.
 */
public class KohaConfiguration implements Configuration {

    private String serviceAddress;
    private boolean trustAllCertificates;
    private String authenticationMethodStrategy;
    private String username;
    private GuardedString password;
    private String clientId;
    private GuardedString clientSecret;

    // Campo para almacenar los mensajes del conector inyectados por el framework
    private ConnectorMessages connectorMessages;

    // ... (todos los getters y setters para las propiedades de configuración permanecen igual)

    @ConfigurationProperty(order = 10,
            displayMessageKey = "serviceAddress.display",
            helpMessageKey = "serviceAddress.help",
            required = true)
    public String getServiceAddress() {
        return serviceAddress;
    }

    public void setServiceAddress(String serviceAddress) {
        this.serviceAddress = serviceAddress;
    }

    @ConfigurationProperty(order = 11,
            displayMessageKey = "rest.config.trustAllCertificates.display",
            helpMessageKey = "rest.config.trustAllCertificates.help")
    public boolean getTrustAllCertificates() {
        return trustAllCertificates;
    }

    public void setTrustAllCertificates(boolean trustAllCertificates) {
        this.trustAllCertificates = trustAllCertificates;
    }

    @ConfigurationProperty(order = 15,
            displayMessageKey = "authenticationMethodStrategy.display",
            helpMessageKey = "authenticationMethodStrategy.help",
            required = true)
    public String getAuthenticationMethodStrategy() {
        return authenticationMethodStrategy;
    }

    public void setAuthenticationMethodStrategy(String authenticationMethodStrategy) {
        this.authenticationMethodStrategy = authenticationMethodStrategy;
    }

    @ConfigurationProperty(order = 20,
            displayMessageKey = "username.display",
            helpMessageKey = "username.help")
    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    @ConfigurationProperty(order = 21,
            confidential = true,
            displayMessageKey = "password.display",
            helpMessageKey = "password.help")
    public GuardedString getPassword() {
        return password;
    }

    public void setPassword(GuardedString password) {
        this.password = password;
    }

    @ConfigurationProperty(order = 30,
            displayMessageKey = "koha.config.clientId.display",
            helpMessageKey = "koha.config.clientId.help")
    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    @ConfigurationProperty(order = 31,
            confidential = true,
            displayMessageKey = "koha.config.clientSecret.display",
            helpMessageKey = "koha.config.clientSecret.help")
    public GuardedString getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(GuardedString clientSecret) {
        this.clientSecret = clientSecret;
    }

    /**
     * Valida que la configuración proporcionada sea coherente y completa.
     */
    @Override
    public void validate() {
        if (serviceAddress == null || serviceAddress.trim().isEmpty()) {
            throw new IllegalArgumentException("La dirección del servicio (serviceAddress) no puede estar vacía.");
        }
        if (authenticationMethodStrategy == null || authenticationMethodStrategy.trim().isEmpty()) {
            throw new IllegalArgumentException("La estrategia de autenticación (authenticationMethodStrategy) es obligatoria.");
        }
        if ("BASIC".equalsIgnoreCase(authenticationMethodStrategy)) {
            if (username == null || username.trim().isEmpty()) {
                throw new IllegalArgumentException("El nombre de usuario (username) es requerido para la autenticación BASIC.");
            }
        } else if ("OAUTH2".equalsIgnoreCase(authenticationMethodStrategy)) {
            if (clientId == null || clientId.trim().isEmpty()) {
                throw new IllegalArgumentException("El Client ID es requerido para la autenticación OAUTH2.");
            }
        }
    }

    /**
     * Método requerido por la interfaz Configuration para inyectar
     * el manejador de mensajes del conector.
     * @param connectorMessages El objeto que maneja los mensajes localizados.
     */
    @Override
    public void setConnectorMessages(ConnectorMessages connectorMessages) {
        this.connectorMessages = connectorMessages;
    }

    /**
     * Getter para el manejador de mensajes.
     * @return El manejador de mensajes del conector.
     */
    public ConnectorMessages getConnectorMessages() {
        return connectorMessages;
    }
}