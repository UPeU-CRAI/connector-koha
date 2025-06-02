package com.identicum.connectors;

import com.evolveum.polygon.rest.AbstractRestConfiguration;
import org.identityconnectors.common.StringUtil;
import org.identityconnectors.common.logging.Log;
import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.common.exceptions.ConfigurationException;
import org.identityconnectors.framework.spi.ConfigurationProperty;

public class RestUsersConfiguration extends AbstractRestConfiguration {

    private static final Log LOG = Log.getLog(RestUsersConfiguration.class);

    // --- OAuth2 Specific Configuration ---
    private String clientId;
    private GuardedString clientSecret;
    private String tokenUrlSuffix = "/api/v1/oauth/token"; // Default value for Koha token endpoint

    /**
     * The Client ID for OAuth2 Client Credentials authentication with Koha.
     * @return The OAuth2 Client ID.
     */
    @ConfigurationProperty(
            displayMessageKey = "koha.config.clientId.display",
            helpMessageKey = "koha.config.clientId.help",
            order = 100, // Controls order in MidPoint UI
            required = false // Required conditionally by validate()
    )
    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    /**
     * The Client Secret for OAuth2 Client Credentials authentication with Koha.
     * @return The OAuth2 Client Secret.
     */
    @ConfigurationProperty(
            displayMessageKey = "koha.config.clientSecret.display",
            helpMessageKey = "koha.config.clientSecret.help",
            confidential = true, // Marks this as a sensitive field
            order = 110,
            required = false // Required conditionally by validate()
    )
    public GuardedString getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(GuardedString clientSecret) {
        this.clientSecret = clientSecret;
    }

    /**
     * The suffix for the OAuth2 token endpoint URL (e.g., "/api/v1/oauth/token").
     * This will be appended to the Service Address (baseUrl).
     * @return The token endpoint URL suffix.
     */
    @ConfigurationProperty(
            displayMessageKey = "koha.config.tokenUrlSuffix.display", // Ensure this key is in Messages.properties
            helpMessageKey = "koha.config.tokenUrlSuffix.help",      // Ensure this key is in Messages.properties
            order = 120,
            required = false // Required conditionally by validate()
    )
    public String getTokenUrlSuffix() {
        return tokenUrlSuffix;
    }

    public void setTokenUrlSuffix(String tokenUrlSuffix) {
        this.tokenUrlSuffix = tokenUrlSuffix;
    }

    /**
     * Validates the connector configuration.
     * This method is called by the framework after all configuration properties have been set.
     */
    @Override
    public void validate() {
        super.validate(); // Validates common properties from AbstractRestConfiguration
        LOG.ok("Validating Koha Connector Configuration...");

        if (StringUtil.isBlank(getServiceAddress())) {
            throw new ConfigurationException("Service Address (serviceAddress) must be configured.");
        }

        boolean hasClientId = StringUtil.isNotBlank(getClientId());
        boolean hasClientSecret = getClientSecret() != null; // GuardedString can be null
        boolean intendsOAuth2 = hasClientId || hasClientSecret;

        String authMethod = getAuthMethod();
        boolean isBasicAuthExplicitlySet = "BASIC".equalsIgnoreCase(authMethod);
        boolean hasBasicUsername = StringUtil.isNotBlank(getUsername());
        boolean hasBasicPassword = getPassword() != null; // GuardedString can be null
        boolean intendsBasicAuth = isBasicAuthExplicitlySet || (hasBasicUsername && hasBasicPassword && StringUtil.isBlank(authMethod));


        if (intendsOAuth2) {
            LOG.ok("OAuth2 configuration detected. Validating OAuth2 parameters.");
            if (!hasClientId) {
                throw new ConfigurationException("Client ID (clientId) must be configured if Client Secret or Token URL Suffix is provided for OAuth2.");
            }
            if (!hasClientSecret) {
                throw new ConfigurationException("Client Secret (clientSecret) must be configured if Client ID is provided for OAuth2.");
            }
            if (StringUtil.isBlank(getTokenUrlSuffix())) {
                throw new ConfigurationException("Token URL Suffix (tokenUrlSuffix) must be configured if Client ID is provided for OAuth2 (e.g., /api/v1/oauth/token).");
            }
            if (isBasicAuthExplicitlySet && intendsBasicAuth) {
                LOG.warn("Both OAuth2 (Client ID/Secret) and Basic Auth (authMethod='BASIC' with username/password) are configured. " +
                        "The connector will prioritize OAuth2 unless authMethod is explicitly 'BASIC'. Please review configuration for clarity.");
            }
        } else if (intendsBasicAuth) {
            LOG.ok("Basic Authentication configuration detected. Validating Basic Auth parameters.");
            if (!isBasicAuthExplicitlySet) {
                LOG.warn("Username/Password are set, but authMethod is not explicitly 'BASIC'. " +
                        "The connector will attempt Basic Auth. Consider setting authMethod to 'BASIC' for clarity.");
            }
            if (!hasBasicUsername) {
                throw new ConfigurationException("Username must be configured for Basic Authentication.");
            }
            if (!hasBasicPassword) {
                throw new ConfigurationException("Password must be configured for Basic Authentication.");
            }
        } else if (StringUtil.isNotBlank(authMethod) && !"NONE".equalsIgnoreCase(authMethod) && !"BASIC".equalsIgnoreCase(authMethod)) {
            LOG.warn("authMethod is set to ''{0}'', but neither OAuth2 nor Basic Auth parameters are fully configured. This may lead to authentication issues.", authMethod);
        }
        else if (StringUtil.isBlank(authMethod) && !intendsOAuth2 && !intendsBasicAuth) {
            LOG.warn("No specific authentication method (OAuth2 or Basic) is fully configured, and authMethod is not set or is 'NONE'. " +
                    "Requests will be anonymous if the API allows. This might fail for most operations.");
            // Consider if anonymous access should be an error:
            // throw new ConfigurationException("No authentication method configured. Please configure OAuth2 or Basic Auth.");
        }

        LOG.ok("Koha Connector Configuration validation completed successfully.");
    }
}
