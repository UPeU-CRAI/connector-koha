package com.identicum.connectors;

import com.evolveum.polygon.rest.AbstractRestConfiguration;
import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.spi.ConfigurationProperty;

public class RestUsersConfiguration extends AbstractRestConfiguration {

    private String clientId;
    private GuardedString clientSecret;
    private String tokenUrlSuffix = "/api/v1/oauth/token"; // Valor por defecto para el endpoint del token de Koha

    /**
     * El Client ID para la autenticación OAuth2 (Client Credentials).
     * Necesario si authMethod es "OAUTH2_CLIENT_CREDENTIALS".
     * @return El Client ID.
     */
    @ConfigurationProperty(
            displayMessageKey = "config.koha.clientId.display",
            helpMessageKey = "config.koha.clientId.help",
            required = false // Requerido solo si authMethod es OAUTH2_CLIENT_CREDENTIALS
    )
    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    /**
     * El Client Secret para la autenticación OAuth2 (Client Credentials).
     * Necesario si authMethod es "OAUTH2_CLIENT_CREDENTIALS".
     * @return El Client Secret.
     */
    @ConfigurationProperty(
            displayMessageKey = "config.koha.clientSecret.display",
            helpMessageKey = "config.koha.clientSecret.help",
            required = false, // Requerido solo si authMethod es OAUTH2_CLIENT_CREDENTIALS
            confidential = true
    )
    public GuardedString getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(GuardedString clientSecret) {
        this.clientSecret = clientSecret;
    }

    /**
     * El sufijo de la URL para obtener el token OAuth2.
     * Se concatenará a serviceAddress. Por defecto es "/api/v1/oauth/token".
     * @return El sufijo de la URL del token.
     */
    @ConfigurationProperty(
            displayMessageKey = "config.koha.tokenUrlSuffix.display",
            helpMessageKey = "config.koha.tokenUrlSuffix.help",
            required = false // Opcional, con valor por defecto
    )
    public String getTokenUrlSuffix() {
        return tokenUrlSuffix;
    }

    public void setTokenUrlSuffix(String tokenUrlSuffix) {
        this.tokenUrlSuffix = tokenUrlSuffix;
    }

    @Override
    public void validate() {
        super.validate(); // Llama a la validación de la clase base

        // Si el método de autenticación es OAuth2 Client Credentials,
        // entonces clientId y clientSecret son obligatorios.
        // Nota: El valor de getAuthMethod() es el que se configura en el XML del recurso.
        //       Deberás asegurarte de que el valor "OAUTH2_CLIENT_CREDENTIALS" (o el que elijas)
        //       sea uno de los soportados por AbstractRestConfiguration o añadirlo si es necesario.
        //       AbstractRestConfiguration soporta "OAUTH_BEARER" como un método donde el token se provee,
        //       pero no maneja el flujo de obtención de token client credentials directamente.
        //       Por ahora, asumiremos que el conector manejará la obtención del token si
        //       se configura un authMethod específico (ej. "OAUTH2_KOHA_CC") o si se dejan username/password
        //       vacíos y clientId/clientSecret están presentes.

        // Por simplicidad, podrías tener una nueva opción en tu 'authMethod'
        // o decidir que si clientId y clientSecret están seteados, se usará OAuth2.
        // Aquí un ejemplo de validación si usas un authMethod específico:

        /*
        if ("OAUTH2_CLIENT_CREDENTIALS_KOHA".equalsIgnoreCase(getAuthMethod())) { // "OAUTH2_CLIENT_CREDENTIALS_KOHA" sería un nuevo valor para authMethod
            if (StringUtil.isBlank(clientId)) {
                throw new ConfigurationException("Client ID must be provided when authMethod is OAuth2 Client Credentials.");
            }
            if (clientSecret == null) { // GuardedString puede ser null pero no estar "blank" en el mismo sentido
                throw new ConfigurationException("Client Secret must be provided when authMethod is OAuth2 Client Credentials.");
            }
            if (StringUtil.isBlank(getTokenUrlSuffix())) {
                 throw new ConfigurationException("Token URL Suffix must be provided when authMethod is OAuth2 Client Credentials.");
            }
        }
        */
        // Alternativamente, si se proveen clientId y clientSecret, y username/password de Basic Auth no,
        // el conector podría inferir que se debe usar OAuth2.
        // Esta validación dependerá de cómo decidas que el conector determine el modo de autenticación.
    }
}