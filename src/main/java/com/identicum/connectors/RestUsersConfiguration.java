package com.identicum.connectors;

import com.evolveum.polygon.rest.AbstractRestConfiguration;
import org.identityconnectors.common.StringUtil;
import org.identityconnectors.common.logging.Log;
import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.common.exceptions.ConfigurationException;
import org.identityconnectors.framework.spi.ConfigurationProperty;

public class RestUsersConfiguration extends AbstractRestConfiguration {

    private static final Log LOG = Log.getLog(RestUsersConfiguration.class);

    // ==== OAuth2 ====
    private String clientId;
    private GuardedString clientSecret;

    @ConfigurationProperty(
            displayMessageKey = "koha.config.clientId.display",
            helpMessageKey = "koha.config.clientId.help",
            required = false
    )
    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    @ConfigurationProperty(
            displayMessageKey = "koha.config.clientSecret.display",
            helpMessageKey = "koha.config.clientSecret.help",
            confidential = true,
            required = false
    )
    public GuardedString getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(GuardedString clientSecret) {
        this.clientSecret = clientSecret;
    }

    @Override
    public void validate() {
        super.validate(); // Valida campos como serviceAddress, username, password, etc.

        boolean hasOAuth2 = !StringUtil.isBlank(getClientId()) && getClientSecret() != null;
        boolean hasBasic = "BASIC".equalsIgnoreCase(getAuthMethod())
                && !StringUtil.isBlank(getUsername())
                && getPassword() != null;

        if (hasOAuth2 && hasBasic) {
            LOG.warn("Ambas credenciales OAuth2 y BASIC están configuradas. El conector priorizará según la lógica en addAuthHeader().");
        }

        if (!hasOAuth2 && !hasBasic) {
            throw new ConfigurationException("No hay credenciales configuradas correctamente. Configure OAuth2 o BASIC.");
        }

        if (StringUtil.isBlank(getServiceAddress())) {
            throw new ConfigurationException("Debe configurar el campo 'Endpoint de API Rest' (serviceAddress).");
        }
    }
}
