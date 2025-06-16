package com.identicum.connectors;

import com.evolveum.polygon.rest.AbstractRestConfiguration;
import org.identityconnectors.common.logging.Log;
import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.spi.ConfigurationProperty;

/**
 * Configuración del conector REST para Koha.
 * Soporta autenticación BASIC y OAuth2 con credenciales de cliente.
 */
public class KohaConfiguration extends AbstractRestConfiguration {

    private static final Log LOG = Log.getLog(KohaConfiguration.class);

    // === API Base ===

    @Override
    @ConfigurationProperty(
            displayMessageKey = "rest.config.trustAllCertificates.display",
            helpMessageKey = "rest.config.trustAllCertificates.help",
            order = 10)
    public Boolean getTrustAllCertificates() {
        return super.getTrustAllCertificates();
    }

    @Override
    @ConfigurationProperty(order = 11,
            displayMessageKey = "serviceAddress.display",
            helpMessageKey = "serviceAddress.help")
    public String getServiceAddress() {
        return super.getServiceAddress();
    }

    private String authenticationMethodStrategy;

    @ConfigurationProperty(
            displayMessageKey = "authenticationMethodStrategy.display",
            helpMessageKey = "authenticationMethodStrategy.help",
            order = 15
    )
    public String getAuthenticationMethodStrategy() {
        return authenticationMethodStrategy;
    }

    public void setAuthenticationMethodStrategy(String authenticationMethodStrategy) {
        this.authenticationMethodStrategy = authenticationMethodStrategy;
    }

    // === Autenticación BASIC ===

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

    // === Autenticación OAuth2 (Client Credentials) ===

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

    // === Ocultar propiedades heredadas que no se usan en el formulario de configuración del conector en Midpoint ===

    @ConfigurationProperty(
            displayMessageKey = "tokenName.display",
            helpMessageKey = "tokenName.help",
            order = Integer.MAX_VALUE // para que aparezca al final, o incluso se oculte
    )
    public String getTokenName() {
        return null;
    }

    @ConfigurationProperty(
            displayMessageKey = "tokenValue.display",
            helpMessageKey = "tokenValue.help",
            order = Integer.MAX_VALUE
    )
    public GuardedString getTokenValue() {
        return null;
    }

}
