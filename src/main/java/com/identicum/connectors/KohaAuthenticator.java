package com.identicum.connectors;

import org.apache.http.HttpException;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.SSLContexts;
import org.apache.http.ssl.TrustStrategy;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.identityconnectors.common.StringUtil;
import org.identityconnectors.common.logging.Log;
import org.identityconnectors.framework.common.exceptions.ConfigurationException;
import org.identityconnectors.framework.common.exceptions.ConnectorIOException;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Gestiona la autenticación para la API de Koha.
 * Puede crear un cliente HTTP pre-autenticado usando Basic Auth o OAuth2.
 */
public class KohaAuthenticator {

    private static final Log LOG = Log.getLog(KohaAuthenticator.class);
    private static final String API_BASE_PATH = "/api/v1";
    private static final int TOKEN_EXPIRY_BUFFER_SECONDS = 60;
    private static final Object tokenLock = new Object();

    private final KohaConfiguration configuration;

    // Campos para el estado del token OAuth2
    private volatile String oauthAccessToken;
    private volatile long oauthTokenExpiryEpoch = 0L;


    public KohaAuthenticator(KohaConfiguration configuration) {
        this.configuration = configuration;
    }

    /**
     * Crea un CloseableHttpClient que añade automáticamente la cabecera de autenticación
     * apropiada (Basic o Bearer) a cada petición.
     *
     * @return Un cliente HTTP listo para usar.
     */
    public CloseableHttpClient createAuthenticatedClient() {
        HttpRequestInterceptor authInterceptor;
        String authMethod = configuration.getAuthenticationMethodStrategy();
        boolean useOAuth2 = StringUtil.isNotBlank(configuration.getClientId()) && StringUtil.isNotBlank(configuration.getClientSecret());

        if (useOAuth2 && !"BASIC".equalsIgnoreCase(authMethod)) {
            // Configurar interceptor para OAuth2
            LOG.ok("AUTH: Configurando cliente HTTP para autenticación OAuth2.");
            authInterceptor = (request, context) -> {
                try {
                    request.setHeader("Authorization", "Bearer " + getValidOAuthToken());
                } catch (IOException e) {
                    throw new HttpException("No se pudo obtener el token de OAuth2", e);
                }
            };
        } else if ("BASIC".equalsIgnoreCase(authMethod)) {
            // Configurar interceptor para Basic Auth
            LOG.ok("AUTH: Configurando cliente HTTP para autenticación BASIC.");
            final String username = configuration.getUsername();
            final String password = configuration.getPassword();

            if (StringUtil.isBlank(username) || StringUtil.isBlank(password)) {
                throw new ConfigurationException("El método de autenticación es BASIC pero el usuario/contraseña no están configurados.");
            }

            authInterceptor = (request, context) -> {
                String auth = username + ":" + password;
                String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
                request.setHeader("Authorization", "Basic " + encodedAuth);
            };
        } else {
            // Sin autenticación específica
            LOG.ok("AUTH: No se configuró un método de autenticación específico. El cliente HTTP no añadirá cabeceras de Auth.");
            authInterceptor = (request, context) -> {
                // No hacer nada
            };
        }

        HttpClientBuilder builder = HttpClients.custom()
                .addInterceptorLast(authInterceptor);

        if (configuration.isTrustAllCertificates()) {
            try {
                TrustStrategy acceptingTrustStrategy = (chain, authType) -> true;
                builder.setSSLContext(SSLContexts.custom()
                        .loadTrustMaterial(null, acceptingTrustStrategy)
                        .build());
                builder.setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE);
            } catch (Exception e) {
                LOG.warn("Error configurando TrustAllCertificates: {0}", e.getMessage());
            }
        }

        return builder.build();
    }

    /**
     * Obtiene un token de acceso OAuth2 válido, ya sea uno en caché o uno nuevo si el anterior expiró.
     * Este método es thread-safe.
     *
     * @return Un token de acceso OAuth2.
     * @throws IOException si hay un problema de comunicación.
     */
    private String getValidOAuthToken() throws IOException {
        synchronized (tokenLock) {
            long nowEpochSeconds = System.currentTimeMillis() / 1000;

            if (StringUtil.isNotBlank(oauthAccessToken) && oauthTokenExpiryEpoch > nowEpochSeconds + TOKEN_EXPIRY_BUFFER_SECONDS) {
                LOG.ok("OAUTH: Reutilizando token de acceso existente.");
                return oauthAccessToken;
            }

            LOG.ok("OAUTH: Solicitud de nuevo token de acceso...");
            String tokenUrl = configuration.getServiceAddress() + API_BASE_PATH + "/oauth/token";

            // Se necesita un cliente HTTP temporal y simple para esta única petición.
            try (CloseableHttpClient tokenClient = HttpClients.createDefault()) {
                HttpPost tokenRequest = new HttpPost(tokenUrl);
                tokenRequest.setHeader("Content-Type", "application/x-www-form-urlencoded");
                tokenRequest.setHeader("Accept", "application/json");

                final String secret = configuration.getClientSecret();

                List<NameValuePair> formParams = new ArrayList<>();
                formParams.add(new BasicNameValuePair("grant_type", "client_credentials"));
                formParams.add(new BasicNameValuePair("client_id", configuration.getClientId()));
                formParams.add(new BasicNameValuePair("client_secret", secret));

                tokenRequest.setEntity(new UrlEncodedFormEntity(formParams, StandardCharsets.UTF_8));

                try (CloseableHttpResponse response = tokenClient.execute(tokenRequest)) {
                    int statusCode = response.getStatusLine().getStatusCode();
                    String body = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);

                    if (statusCode < 200 || statusCode >= 300) {
                        LOG.error("OAUTH: Error al obtener token. Status: {0}, Body: {1}", statusCode, body);
                        throw new ConnectorIOException("OAUTH: Falló la solicitud de token. Status: " + statusCode);
                    }

                    JSONObject json = new JSONObject(body);
                    oauthAccessToken = json.getString("access_token");
                    int expiresIn = json.optInt("expires_in", 3600);
                    oauthTokenExpiryEpoch = nowEpochSeconds + expiresIn;

                    LOG.ok("OAUTH: Nuevo token obtenido. Expira en {0} segundos.", expiresIn);
                    return oauthAccessToken;

                } catch (JSONException e) {
                    LOG.error("OAUTH: Error al parsear la respuesta del token: {0}", e.getMessage(), e);
                    this.oauthAccessToken = null;
                    this.oauthTokenExpiryEpoch = 0L;
                    throw new ConnectorIOException("OAUTH: Respuesta de token inválida: " + e.getMessage(), e);
                }
            }
        }
    }
}
