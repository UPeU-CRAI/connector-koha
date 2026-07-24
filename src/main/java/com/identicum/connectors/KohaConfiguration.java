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
    /**
     * If true, all SSL certificates are trusted (including self-signed).
     * Default is false. Only enable in development/testing environments.
     */
    private boolean trustAllCertificates = false;
    private String authenticationMethodStrategy;
    private String username;
    private GuardedString password;
    private String clientId;
    private GuardedString clientSecret;
    private int pageSize = 100;

    // --- Canal JDBC (autorizacion del patron) ---
    /**
     * If true, the connector opens a direct JDBC channel to the Koha database to
     * provision patron authorization: {@code borrowers.flags} and {@code user_permissions}.
     * The Koha REST API exposes neither, hence this hybrid design.
     * Default is false (REST-only behaviour, fully backwards compatible).
     *
     * <p>NOTE: this channel does NOT handle patron photos. Provisioning binary photos
     * from MidPoint is deliberately out of scope (removed in v1.5.0).</p>
     */
    private boolean dbEnabled = false;
    private String dbHost;
    private int dbPort = 3306;
    private String dbName;
    private String dbUser;
    private GuardedString dbPassword;
    private int dbPoolSize = 2;

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

    @ConfigurationProperty(order = 40,
            displayMessageKey = "koha.config.pageSize.display",
            helpMessageKey = "koha.config.pageSize.help")
    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    // --- Propiedades del canal JDBC ---

    @ConfigurationProperty(order = 50,
            displayMessageKey = "koha.config.dbEnabled.display",
            helpMessageKey = "koha.config.dbEnabled.help")
    public boolean getDbEnabled() {
        return dbEnabled;
    }

    public void setDbEnabled(boolean dbEnabled) {
        this.dbEnabled = dbEnabled;
    }

    @ConfigurationProperty(order = 51,
            displayMessageKey = "koha.config.dbHost.display",
            helpMessageKey = "koha.config.dbHost.help")
    public String getDbHost() {
        return dbHost;
    }

    public void setDbHost(String dbHost) {
        this.dbHost = dbHost;
    }

    @ConfigurationProperty(order = 52,
            displayMessageKey = "koha.config.dbPort.display",
            helpMessageKey = "koha.config.dbPort.help")
    public int getDbPort() {
        return dbPort;
    }

    public void setDbPort(int dbPort) {
        this.dbPort = dbPort;
    }

    @ConfigurationProperty(order = 53,
            displayMessageKey = "koha.config.dbName.display",
            helpMessageKey = "koha.config.dbName.help")
    public String getDbName() {
        return dbName;
    }

    public void setDbName(String dbName) {
        this.dbName = dbName;
    }

    @ConfigurationProperty(order = 54,
            displayMessageKey = "koha.config.dbUser.display",
            helpMessageKey = "koha.config.dbUser.help")
    public String getDbUser() {
        return dbUser;
    }

    public void setDbUser(String dbUser) {
        this.dbUser = dbUser;
    }

    @ConfigurationProperty(order = 55,
            confidential = true,
            displayMessageKey = "koha.config.dbPassword.display",
            helpMessageKey = "koha.config.dbPassword.help")
    public GuardedString getDbPassword() {
        return dbPassword;
    }

    public void setDbPassword(GuardedString dbPassword) {
        this.dbPassword = dbPassword;
    }

    @ConfigurationProperty(order = 56,
            displayMessageKey = "koha.config.dbPoolSize.display",
            helpMessageKey = "koha.config.dbPoolSize.help")
    public int getDbPoolSize() {
        return dbPoolSize;
    }

    public void setDbPoolSize(int dbPoolSize) {
        this.dbPoolSize = dbPoolSize;
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
            if (password == null) {
                throw new IllegalArgumentException("La contrasena (password) es requerida para la autenticacion BASIC.");
            }
        } else if ("OAUTH2".equalsIgnoreCase(authenticationMethodStrategy)) {
            if (clientId == null || clientId.trim().isEmpty()) {
                throw new IllegalArgumentException("El Client ID es requerido para la autenticación OAUTH2.");
            }
        } else {
            throw new IllegalArgumentException("El valor de authenticationMethodStrategy no es reconocido: '" + authenticationMethodStrategy + "'. Valores válidos: BASIC, OAUTH2.");
        }

        // --- Validación del canal JDBC ---
        if (dbEnabled) {
            if (dbHost == null || dbHost.trim().isEmpty()) {
                throw new IllegalArgumentException("El host de la base de datos (dbHost) es requerido cuando dbEnabled=true.");
            }
            if (dbName == null || dbName.trim().isEmpty()) {
                throw new IllegalArgumentException("El nombre de la base de datos (dbName) es requerido cuando dbEnabled=true.");
            }
            if (dbUser == null || dbUser.trim().isEmpty()) {
                throw new IllegalArgumentException("El usuario de la base de datos (dbUser) es requerido cuando dbEnabled=true.");
            }
            if (dbPassword == null) {
                throw new IllegalArgumentException("La contrasena de la base de datos (dbPassword) es requerida cuando dbEnabled=true.");
            }
            if (dbPort < 1 || dbPort > 65535) {
                throw new IllegalArgumentException("El puerto de la base de datos (dbPort) debe estar entre 1 y 65535. Valor actual: " + dbPort);
            }
            if (dbPoolSize < 1) {
                throw new IllegalArgumentException("El tamano del pool JDBC (dbPoolSize) debe ser >= 1. Valor actual: " + dbPoolSize);
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