package com.identicum.connectors.services;

import com.identicum.connectors.KohaFilter;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.*;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.identityconnectors.common.StringUtil;
import org.identityconnectors.common.logging.Log;
import org.identityconnectors.framework.common.exceptions.ConnectorException;
import org.identityconnectors.framework.common.exceptions.ConnectorIOException;
import org.identityconnectors.framework.common.objects.OperationOptions;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Servicio para gestionar las operaciones CRUD y de búsqueda para los Patrones de Koha.
 */
public class PatronService {

    private static final Log LOG = Log.getLog(PatronService.class);
    private static final String API_BASE_PATH = "/api/v1";
    private static final String ENDPOINT = "/patrons";

    private final CloseableHttpClient httpClient;
    private final String serviceAddress;

    public PatronService(CloseableHttpClient httpClient, String serviceAddress) {
        this.httpClient = httpClient;
        this.serviceAddress = serviceAddress;
    }

    private String getBaseUrl() {
        return this.serviceAddress + API_BASE_PATH + ENDPOINT;
    }

    public JSONObject getPatron(String uid) throws IOException {
        HttpGet request = new HttpGet(getBaseUrl() + "/" + uid);
        String responseBody = callRequest(request);
        return new JSONObject(responseBody);
    }

    public JSONObject createPatron(JSONObject payload) throws IOException {
        HttpPost request = new HttpPost(getBaseUrl());
        return callRequestWithEntity(request, payload);
    }

    public void updatePatron(String uid, JSONObject payload) throws IOException {
        HttpPut request = new HttpPut(getBaseUrl() + "/" + uid);
        callRequestWithEntity(request, payload);
    }

    public void deletePatron(String uid) throws IOException {
        HttpDelete request = new HttpDelete(getBaseUrl() + "/" + uid);
        callRequest(request);
    }

    public JSONArray searchPatrons(KohaFilter filter, OperationOptions opts) throws IOException {
        JSONArray allResults = new JSONArray();
        int pageSize = (opts != null && opts.getPageSize() != null) ? opts.getPageSize() : 100;
        int currentPage = 1;
        boolean moreResults;

        do {
            List<String> queryParams = new ArrayList<>();
            queryParams.add("_per_page=" + pageSize);
            queryParams.add("_page=" + currentPage);

            if (filter != null) {
                if (StringUtil.isNotBlank(filter.getByName())) queryParams.add("userid=" + urlEncodeUTF8(filter.getByName()));
                if (StringUtil.isNotBlank(filter.getByEmail())) queryParams.add("email=" + urlEncodeUTF8(filter.getByEmail()));
                if (filter.getByCardNumber() != null) queryParams.add("cardnumber=" + urlEncodeUTF8(filter.getByCardNumber()));
            }

            String fullUrl = getBaseUrl() + "?" + String.join("&", queryParams);
            HttpGet request = new HttpGet(fullUrl);
            LOG.ok("PATRON_SEARCH: URL: {0}", request.getURI());

            String response = callRequest(request);
            JSONArray pageResults;

            // Si la respuesta está vacía (posiblemente por paginación que ya no devuelve más), no hacer nada.
            if (StringUtil.isBlank(response)) {
                pageResults = new JSONArray();
            } else {
                try {
                    pageResults = new JSONArray(response);
                } catch (JSONException e) {
                    try {
                        JSONObject responseObject = new JSONObject(response);
                        pageResults = responseObject.optJSONArray("patrons");
                        if (pageResults == null) {
                            pageResults = new JSONArray(Arrays.asList(responseObject));
                        }
                    } catch (JSONException ex) {
                        throw new ConnectorException("Respuesta JSON inválida de Koha al buscar patrones: " + response, ex);
                    }
                }
            }

            if (pageResults.length() > 0) {
                for (int i = 0; i < pageResults.length(); i++) {
                    allResults.put(pageResults.getJSONObject(i));
                }
            }

            moreResults = pageResults.length() == pageSize;
            if (moreResults) {
                currentPage++;
            }

        } while (moreResults);

        return allResults;
    }

    // --- Métodos de ayuda para ejecutar peticiones HTTP ---

    private JSONObject callRequestWithEntity(HttpEntityEnclosingRequestBase request, JSONObject payload) throws IOException {
        request.setHeader("Content-Type", "application/json");
        request.setHeader("Accept", "application/json");
        if (payload != null) {
            request.setEntity(new ByteArrayEntity(payload.toString().getBytes(StandardCharsets.UTF_8)));
        }

        try (CloseableHttpResponse response = httpClient.execute(request)) {
            processResponseErrors(response);
            String result = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            if (response.getStatusLine().getStatusCode() == 204 || StringUtil.isBlank(result)) {
                return new JSONObject();
            }
            return new JSONObject(result);
        } catch (JSONException e) {
            throw new ConnectorException("Fallo al parsear respuesta JSON para " + request.getURI(), e);
        }
    }

    private String callRequest(HttpRequestBase request) throws IOException {
        request.setHeader("Accept", "application/json");
        request.setHeader("Accept-Encoding", "gzip");

        try (CloseableHttpResponse response = httpClient.execute(request)) {
            processResponseErrors(response);
            HttpEntity entity = response.getEntity();

            // --- CORRECCIÓN APLICADA ---
            if (entity == null) {
                return ""; // Devolver un string vacío si no hay cuerpo de respuesta
            }

            InputStream inputStream = entity.getContent();
            Header contentEncoding = entity.getContentEncoding();
            if (contentEncoding != null && "gzip".equalsIgnoreCase(contentEncoding.getValue())) {
                inputStream = new GZIPInputStream(inputStream);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void processResponseErrors(CloseableHttpResponse response) throws ConnectorIOException {
        int statusCode = response.getStatusLine().getStatusCode();
        if (statusCode >= 200 && statusCode < 300) {
            return; // Sin error
        }

        String body = "";
        try {
            HttpEntity entity = response.getEntity();
            if (entity != null) {
                body = EntityUtils.toString(entity);
            }
        } catch (IOException e) {
            // No hacer nada, el error principal es el status code
        }

        LOG.error("Error en la respuesta HTTP. Status: {0}, Razón: {1}, Cuerpo: {2}",
                statusCode, response.getStatusLine().getReasonPhrase(), body);

        throw new ConnectorIOException("Error en la API de Koha. Status: " + statusCode + " " + response.getStatusLine().getReasonPhrase());
    }

    private String urlEncodeUTF8(String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            throw new ConnectorException("La codificación UTF-8 no está soportada, esto no debería ocurrir.", e);
        }
    }
}
