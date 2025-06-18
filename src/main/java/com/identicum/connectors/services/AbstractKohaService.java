package com.identicum.connectors.services;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.conn.HttpHostConnectException;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.identityconnectors.common.StringUtil;
import org.identityconnectors.common.logging.Log;
import org.identityconnectors.framework.common.exceptions.AlreadyExistsException;
import org.identityconnectors.framework.common.exceptions.ConnectionFailedException;
import org.identityconnectors.framework.common.exceptions.ConnectorException;
import org.identityconnectors.framework.common.exceptions.ConnectorIOException;
import org.identityconnectors.framework.common.exceptions.ConnectorRuntimeException;
import org.identityconnectors.framework.common.exceptions.InvalidAttributeValueException;
import org.identityconnectors.framework.common.exceptions.PermissionDeniedException;
import org.identityconnectors.framework.common.exceptions.UnknownUidException;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.SocketTimeoutException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

public abstract class AbstractKohaService {

    private static final Log LOG = Log.getLog(AbstractKohaService.class);
    protected final CloseableHttpClient httpClient;
    protected final String serviceAddress;

    public AbstractKohaService(CloseableHttpClient httpClient, String serviceAddress) {
        this.httpClient = httpClient;
        this.serviceAddress = serviceAddress;
    }

    protected abstract String getEndpoint();
    protected abstract String getResourceName();

    protected String getBaseUrl() {
        return this.serviceAddress + "/api/v1" + getEndpoint();
    }

    protected JSONObject callRequestWithEntity(HttpEntityEnclosingRequestBase request, JSONObject payload) throws ConnectorException, IOException {
        request.setHeader("Content-Type", "application/json");
        request.setHeader("Accept", "application/json");
        if (payload != null) {
            request.setEntity(new ByteArrayEntity(payload.toString().getBytes(StandardCharsets.UTF_8)));
        }

        LOG.ok("Trace Mapper: Executing {0} request to {1}", request.getMethod(), request.getURI());
        if (payload != null) {
            LOG.ok("Trace Mapper: Request payload for {0} {1}: {2}", request.getMethod(), request.getURI(), payload.toString(2));
        }

        try (CloseableHttpResponse response = httpClient.execute(request)) {
            processResponseErrors(response, request);
            String result = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            // If status code is 204 (No Content) or the result is blank, return an empty JSON object.
            // This handles cases where the API successfully processes a request but doesn't return a body (e.g., PUT/DELETE),
            // or when a POST might return 201 Created without a body (though often it returns the created resource).
            if (response.getStatusLine().getStatusCode() == 204 || StringUtil.isBlank(result)) {
                LOG.ok("Trace Mapper: Response for {0} {1}: Empty or No Content.", request.getMethod(), request.getURI());
                return new JSONObject();
            }
            LOG.ok("Trace Mapper: Response body for {0} {1}: {2}", request.getMethod(), request.getURI(), result);
            return new JSONObject(result);
        } catch (HttpHostConnectException e) {
            LOG.error(e, "Connection to Koha service at ''{0}'' failed for {1} {2}.", serviceAddress, request.getMethod(), request.getURI());
            throw new ConnectionFailedException("Connection to Koha service at '" + serviceAddress + "' failed. Details: " + e.getMessage(), e);
        } catch (SocketTimeoutException e) {
            LOG.error(e, "Connection to Koha service timed out for {0} {1}.", request.getMethod(), request.getURI());
            throw new ConnectionFailedException("Connection to Koha service timed out for request to '" + request.getURI() + "'. Details: " + e.getMessage(), e);
        } catch (ClientProtocolException e) {
            LOG.error(e, "HTTP protocol error during {0} {1}.", request.getMethod(), request.getURI());
            throw new ConnectorIOException("HTTP protocol error during request to '" + request.getURI() + "'. Details: " + e.getMessage(), e);
        } catch (IOException e) {
            LOG.error(e, "IO error during {0} {1}.", request.getMethod(), request.getURI());
            throw new ConnectorIOException("IO error during request to '" + request.getURI() + "'. Details: " + e.getMessage(), e);
        } catch (JSONException e) {
            LOG.error(e, "Failed to parse JSON response for {0} {1}.", request.getMethod(), request.getURI());
            throw new ConnectorException("Failed to parse JSON response from " + request.getURI() + ". Details: " + e.getMessage(), e);
        }
    }

    protected String callRequest(HttpRequestBase request) throws ConnectorException, IOException {
        request.setHeader("Accept", "application/json");
        request.setHeader("Accept-Encoding", "gzip");

        LOG.ok("Trace Mapper: Executing {0} request to {1}", request.getMethod(), request.getURI());

        try (CloseableHttpResponse response = httpClient.execute(request)) {
            processResponseErrors(response, request);
            HttpEntity entity = response.getEntity();
            if (entity == null) {
                // If there's no entity (e.g. 204 No Content), return empty string.
                // This is consistent with how EntityUtils.toString(null) would behave if not for our check.
                LOG.ok("Trace Mapper: Response for {0} {1}: No entity in response.", request.getMethod(), request.getURI());
                return "";
            }
            InputStream inputStream = entity.getContent();
            Header contentEncoding = entity.getContentEncoding();
            if (contentEncoding != null && "gzip".equalsIgnoreCase(contentEncoding.getValue())) {
                inputStream = new GZIPInputStream(inputStream);
            }

            // Read the input stream into a byte array before converting to string
            // This avoids issues with character encoding if the stream is read incrementally as chars.
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            int nRead;
            byte[] data = new byte[4096]; // Buffer size
            while ((nRead = inputStream.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
            }
            buffer.flush();
            byte[] bytes = buffer.toByteArray();
            String responseBodyString = new String(bytes, StandardCharsets.UTF_8);
            LOG.ok("Trace Mapper: Response body for {0} {1}: {2}", request.getMethod(), request.getURI(), responseBodyString);
            return responseBodyString;
        } catch (HttpHostConnectException e) {
            LOG.error(e, "Connection to Koha service at ''{0}'' failed for {1} {2}.", serviceAddress, request.getMethod(), request.getURI());
            throw new ConnectionFailedException("Connection to Koha service at '" + serviceAddress + "' failed. Details: " + e.getMessage(), e);
        } catch (SocketTimeoutException e) {
            LOG.error(e, "Connection to Koha service timed out for {0} {1}.", request.getMethod(), request.getURI());
            throw new ConnectionFailedException("Connection to Koha service timed out for request to '" + request.getURI() + "'. Details: " + e.getMessage(), e);
        } catch (ClientProtocolException e) {
            LOG.error(e, "HTTP protocol error during {0} {1}.", request.getMethod(), request.getURI());
            throw new ConnectorIOException("HTTP protocol error during request to '" + request.getURI() + "'. Details: " + e.getMessage(), e);
        } catch (IOException e) {
            LOG.error(e, "IO error during {0} {1}.", request.getMethod(), request.getURI());
            throw new ConnectorIOException("IO error during request to '" + request.getURI() + "'. Details: " + e.getMessage(), e);
        }
    }

    protected void processResponseErrors(CloseableHttpResponse response, HttpRequestBase request) throws ConnectorException {
        int statusCode = response.getStatusLine().getStatusCode();
        if (statusCode >= 200 && statusCode < 300) {
            return; // No error
        }

        String body = "";
        try {
            HttpEntity entity = response.getEntity();
            if (entity != null) {
                body = EntityUtils.toString(entity); // Read the body for error reporting
            }
        } catch (IOException e) {
            LOG.warn(e, "Error reading error response body for {0} {1}.", request.getMethod(), request.getURI());
        }

        LOG.error("Error in HTTP response for {0} {1}. Status: {2}, Reason: {3}, Body: {4}",
                request.getMethod(), request.getURI(), statusCode, response.getStatusLine().getReasonPhrase(), body);

        String resourceContext = "Koha " + getResourceName() + " API";
        String requestDesc = request.getMethod() + " " + request.getURI();

        switch (statusCode) {
            case 400:
                String lowerBody = body.toLowerCase();
                // More specific checks for "already exists" based on resource type
                if (getResourceName().equalsIgnoreCase("patron") &&
                    (lowerBody.contains("patron already exists") || lowerBody.contains("cardnumber already exists"))) {
                    throw new AlreadyExistsException(resourceContext + " reported resource already exists. Request: " + requestDesc + ", Body: " + body);
                } else if (getResourceName().equalsIgnoreCase("category") &&
                           (lowerBody.contains("already exists") || lowerBody.contains("categorycode_exists") || lowerBody.contains("description_already_exists"))) {
                    throw new AlreadyExistsException(resourceContext + " reported resource already exists. Request: " + requestDesc + ", Body: " + body);
                }
                throw new InvalidAttributeValueException(resourceContext + " Bad Request. Request: " + requestDesc + ", Status: " + statusCode + ", Body: " + body);
            case 401:
                throw new PermissionDeniedException("Authentication failed for " + resourceContext + ". Request: " + requestDesc + ", Status: " + statusCode + ", Body: " + body);
            case 403:
                throw new PermissionDeniedException("Permission denied for " + resourceContext + ". Request: " + requestDesc + ", Status: " + statusCode + ", Body: " + body);
            case 404:
                String uidPart = "";
                // Attempt to extract last part of path as potential UID/ID
                String[] pathSegments = request.getURI().getPath().split("/");
                if (pathSegments.length > 0) {
                    uidPart = pathSegments[pathSegments.length -1];
                }
                // Only throw UnknownUidException for operations that typically target a specific resource by ID
                if (request.getMethod().equals("GET") || request.getMethod().equals("PUT") || request.getMethod().equals("DELETE")) {
                    throw new UnknownUidException("Koha " + getResourceName() + " not found (ID/Code: " + uidPart + "). Request: " + requestDesc + ", Status: " + statusCode + ", Body: " + body);
                }
                // For other methods like POST to a non-existent base path, or other 404s.
                throw new ConnectorIOException(resourceContext + " endpoint/resource not found. Request: " + requestDesc + ", Status: " + statusCode + ", Body: " + body);
            case 409: // Conflict
                throw new AlreadyExistsException(resourceContext + " Conflict (e.g. resource version mismatch, or state prevents operation). Request: " + requestDesc + ", Status: " + statusCode + ", Body: " + body);
            case 500:
            case 502:
            case 503:
            case 504:
                throw new ConnectionFailedException(resourceContext + " server error or temporary unavailability. Request: " + requestDesc + ", Status: " + statusCode + ", Body: " + body);
            default:
                throw new ConnectorIOException("Unhandled " + resourceContext + " error. Request: " + requestDesc + ", Status: " + statusCode + ", Body: " + body);
        }
    }

    protected String urlEncodeUTF8(String s) {
        if (s == null) return ""; // Handle null input gracefully
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            // This should be virtually impossible on any modern Java platform
            LOG.error(e, "UTF-8 encoding not supported, which is highly unusual.");
            // Usar RuntimeException como fallback temporal si ConnectorRuntimeException no se encuentra.
            throw new ConnectorRuntimeException("UTF-8 encoding not supported. Original error: " + e.getMessage(), e);
        }
    }
}
