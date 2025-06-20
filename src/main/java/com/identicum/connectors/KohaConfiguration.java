package com.identicum.connectors;

import com.evolveum.polygon.rest.AbstractRestConfiguration;
import org.identityconnectors.common.logging.Log;
import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.spi.ConfigurationProperty;

/**
 * Configuración del conector REST para Koha.
 * Soporta autenticación BASIC y OAuth2 con credenciales de cliente.
 * Las propiedades están ordenadas para una presentación lógica en la UI de Midpoint.
 */
public class KohaConfiguration extends AbstractRestConfiguration {

    private static final Log LOG = Log.getLog(KohaConfiguration.class);

    // === 1. Configuración Base de la API (Común a ambos métodos) ===

    @Override
    @ConfigurationProperty(order = 10,
            displayMessageKey = "serviceAddress.display",
            helpMessageKey = "serviceAddress.help")
    public String getServiceAddress() {
        return super.getServiceAddress();
    }

    /**
     * Valor de la propiedad {@code trustAllCertificates}. Cuando es {@code true}
     * el cliente HTTP acepta cualquier certificado SSL.
     */
    @Override
    @ConfigurationProperty(
            order = 11,
            displayMessageKey = "rest.config.trustAllCertificates.display",
            helpMessageKey = "rest.config.trustAllCertificates.help")
    public Boolean getTrustAllCertificates() {
        return super.getTrustAllCertificates();
    }

    // === 2. Estrategia de Autenticación ===

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

    // === 3. Grupo de Autenticación BASIC ===

    @Override
    @ConfigurationProperty(order = 20,
            displayMessageKey = "username.display",
            helpMessageKey = "username.help")
    public String getUsername() {
        return super.getUsername();
    }

    @Override
    @ConfigurationProperty(order = 21, confidential = true,
            displayMessageKey = "password.display",
            helpMessageKey = "password.help")
    public GuardedString getPassword() {
        return super.getPassword();
    }

    // === 4. Grupo de Autenticación OAuth2 (Client Credentials) ===

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

    // === 5. Ocultar propiedades heredadas que no se usan ===
    // Anulamos los métodos de la clase base y les asignamos un 'order' muy alto
    // para que no aparezcan en la parte principal del formulario de Midpoint.

    @Override
    @ConfigurationProperty(order = 100, displayMessageKey = "proxy.display", helpMessageKey = "proxy.help")
    public String getProxyHost() { return null; }

    @Override
    @ConfigurationProperty(order = 101, displayMessageKey = "proxyPort.display", helpMessageKey = "proxyPort.help")
    public String getProxyPort() { return null; }

    @Override
    @ConfigurationProperty(order = 102, displayMessageKey = "authMethod.display", helpMessageKey = "authMethod.help")
    public String getAuthMethod() { return null; }

    @Override
    @ConfigurationProperty(order = 103, displayMessageKey = "tokenName.display", helpMessageKey = "tokenName.help")
    public String getTokenName() {
        return null;
    }

    @Override
    @ConfigurationProperty(order = 104, displayMessageKey = "tokenValue.display", helpMessageKey = "tokenValue.help")
    public GuardedString getTokenValue() {
        return null;
    }
}
