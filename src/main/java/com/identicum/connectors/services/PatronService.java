package com.identicum.connectors.services;

import com.identicum.connectors.KohaConfiguration;
import com.identicum.connectors.KohaFilter;
import com.identicum.connectors.services.HttpClientAdapter;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.identityconnectors.common.StringUtil;
import org.identityconnectors.common.logging.Log;
import org.identityconnectors.framework.common.exceptions.AlreadyExistsException;
import org.identityconnectors.framework.common.exceptions.ConnectorException;
import org.identityconnectors.framework.common.objects.OperationOptions;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Servicio para gestionar las operaciones CRUD y de búsqueda para los Patrones de Koha.
 */
public class PatronService extends AbstractKohaService {

    private static final Log LOG = Log.getLog(PatronService.class);
    private final KohaConfiguration configuration;

    public PatronService(HttpClientAdapter httpClient, String serviceAddress, KohaConfiguration configuration) {
        super(httpClient, serviceAddress);
        this.configuration = configuration;
    }

    @Override
    protected String getEndpoint() {
        return "/patrons";
    }

    @Override
    protected String getResourceName() {
        return "patron";
    }

    public JSONObject getPatron(String uid) throws ConnectorException, IOException {
        HttpGet request = new HttpGet(getBaseUrl() + "/" + uid);
        request.setHeader("x-koha-embed", "extended_attributes");
        String responseBody = callRequest(request);
        try {
            if (StringUtil.isBlank(responseBody)) {
                return new JSONObject();
            }
            return new JSONObject(responseBody);
        } catch (JSONException e) {
            throw new ConnectorException("Failed to parse JSON response for getPatron UID " + uid + ". Response: " + responseBody, e);
        }
    }

    /**
     * Crea un patron en Koha. Si Koha devuelve 409 Conflict con el mensaje
     * "already exists", busca el patron existente por cardnumber y devuelve
     * su JSONObject, haciendo la operacion idempotente.
     *
     * Esto resuelve el caso de shadow muerta/perdida en MidPoint: en lugar de
     * fallar el recompute, el conector devuelve el UID del patron existente
     * para que MidPoint lo vincule correctamente.
     */
    public JSONObject createPatron(JSONObject payload) throws ConnectorException, IOException {
        HttpPost request = new HttpPost(getBaseUrl());
        try {
            return callRequestWithEntity(request, payload);
        } catch (AlreadyExistsException e) {
            // Koha devolvio 409 — puede ser shadow muerta. Intentar recuperar por cardnumber.
            String cardnumber = payload.optString("cardnumber", null);
            if (cardnumber == null || cardnumber.isEmpty()) {
                LOG.warn("CREATE patron devolvio 409 pero el payload no contiene cardnumber. Relanzando excepcion original.");
                throw e;
            }
            LOG.info("CREATE patron 409 conflict para cardnumber={0}. Buscando patron existente para recuperar UID.", cardnumber);
            JSONObject existing = findPatronByCardnumber(cardnumber);
            if (existing != null && existing.has("patron_id")) {
                LOG.ok("Patron existente encontrado via cardnumber={0}, patron_id={1}. Devolviendo UID existente (operacion idempotente).",
                        cardnumber, existing.get("patron_id"));
                return existing;
            }
            LOG.warn("CREATE patron 409 para cardnumber={0} pero no se encontro patron existente en GET. Relanzando excepcion original.", cardnumber);
            throw e;
        }
    }

    /**
     * Busca un patron por cardnumber. Devuelve el primer resultado o null si no existe.
     */
    private JSONObject findPatronByCardnumber(String cardnumber) throws ConnectorException, IOException {
        String url = getBaseUrl() + "?cardnumber=" + urlEncodeUTF8(cardnumber) + "&_per_page=1&_page=1";
        HttpGet request = new HttpGet(url);
        request.setHeader("x-koha-embed", "extended_attributes");
        String responseBody = callRequest(request);
        if (responseBody == null || responseBody.trim().isEmpty()) {
            return null;
        }
        try {
            String trimmed = responseBody.trim();
            if (trimmed.startsWith("[")) {
                org.json.JSONArray arr = new org.json.JSONArray(trimmed);
                if (arr.length() > 0) {
                    return arr.getJSONObject(0);
                }
                return null;
            } else if (trimmed.startsWith("{")) {
                JSONObject obj = new JSONObject(trimmed);
                if (obj.has("patron_id")) {
                    return obj;
                }
                return null;
            }
            return null;
        } catch (org.json.JSONException je) {
            LOG.warn("No se pudo parsear la respuesta al buscar patron por cardnumber={0}: {1}", cardnumber, responseBody);
            return null;
        }
    }

    public void updatePatron(String uid, JSONObject payload) throws ConnectorException, IOException {
        HttpPut request = new HttpPut(getBaseUrl() + "/" + uid);
        callRequestWithEntity(request, payload);
    }

    public void deletePatron(String uid) throws ConnectorException, IOException {
        HttpDelete request = new HttpDelete(getBaseUrl() + "/" + uid);
        callRequest(request);
    }

    /**
     * Simple connectivity test: fetches a single patron page without pagination loop.
     */
    public void testConnection() throws ConnectorException, IOException {
        HttpGet request = new HttpGet(getBaseUrl() + "?_per_page=1&_page=1");
        request.setHeader("Accept", "application/json");
        callRequest(request);
    }

    public void searchPatrons(KohaFilter filter, OperationOptions opts, Predicate<JSONObject> consumer) throws ConnectorException, IOException {
        int pageSize = (opts != null && opts.getPageSize() != null) ? opts.getPageSize() : configuration.getPageSize();

        // Determinar si aplica fallback cardnumber:
        // Solo cuando se busca por __NAME__ (userid) con match exacto y sin otros criterios de filtrado.
        boolean useCardnumberFallback = filter != null
                && StringUtil.isNotBlank(filter.getByName())
                && filter.getByCardNumber() == null
                && StringUtil.isBlank(filter.getByEmail())
                && StringUtil.isBlank(filter.getByCategoryId())
                && StringUtil.isBlank(filter.getByLibraryId())
                && (filter.getMatchType() == null || "exact".equals(filter.getMatchType()));

        long delivered = executePagedSearch(filter, pageSize, consumer);

        if (delivered == 0 && useCardnumberFallback) {
            LOG.info("PATRON_SEARCH: userid={0} no encontrado. Reintentando con cardnumber={0}.", filter.getByName());
            KohaFilter fallbackFilter = new KohaFilter();
            fallbackFilter.setByCardNumber(filter.getByName());
            executePagedSearch(fallbackFilter, pageSize, consumer);
        }
    }

    /**
     * Ejecuta la búsqueda paginada aplicando los criterios del filtro dado.
     * Retorna el número total de patrones entregados al consumer.
     */
    private long executePagedSearch(KohaFilter filter, int pageSize, Predicate<JSONObject> consumer) throws ConnectorException, IOException {
        int currentPage = 1;
        int pageCount = 0;
        long totalDelivered = 0;
        final int MAX_PAGES = 1000;
        boolean moreResults;
        String fullUrl;

        outer:
        do {
            List<String> queryParams = new ArrayList<>();
            queryParams.add("_per_page=" + pageSize);
            queryParams.add("_page=" + currentPage);

            if (filter != null) {
                if (StringUtil.isNotBlank(filter.getByName())) queryParams.add("userid=" + urlEncodeUTF8(filter.getByName()));
                if (StringUtil.isNotBlank(filter.getByEmail())) queryParams.add("email=" + urlEncodeUTF8(filter.getByEmail()));
                if (filter.getByCardNumber() != null) queryParams.add("cardnumber=" + urlEncodeUTF8(filter.getByCardNumber()));
                if (StringUtil.isNotBlank(filter.getByCategoryId())) queryParams.add("category_id=" + urlEncodeUTF8(filter.getByCategoryId()));
                if (StringUtil.isNotBlank(filter.getByLibraryId())) queryParams.add("library_id=" + urlEncodeUTF8(filter.getByLibraryId()));
                if (filter.getMatchType() != null && !"exact".equals(filter.getMatchType())) {
                    queryParams.add("_match=" + urlEncodeUTF8(filter.getMatchType()));
                }
            }

            fullUrl = getBaseUrl() + "?" + String.join("&", queryParams);
            HttpGet request = new HttpGet(fullUrl);
            request.setHeader("x-koha-embed", "extended_attributes");
            LOG.info("PATRON_SEARCH: URL: {0}", request.getURI());

            AbstractKohaService.HttpResult httpResult = callRequestFull(request);
            String response = httpResult.getBody();
            JSONArray pageResults;

            if (StringUtil.isBlank(response)) {
                pageResults = new JSONArray();
            } else {
                try {
                    if (response.trim().startsWith("{")) {
                        JSONObject responseObject = new JSONObject(response);
                        if (responseObject.has("patrons") && responseObject.get("patrons") instanceof JSONArray) {
                            pageResults = responseObject.getJSONArray("patrons");
                        } else {
                            pageResults = new JSONArray();
                            pageResults.put(responseObject);
                        }
                    } else if (response.trim().startsWith("[")) {
                        pageResults = new JSONArray(response);
                    } else {
                        throw new JSONException("Response is neither a JSON object nor a JSON array.");
                    }
                } catch (JSONException e) {
                    throw new ConnectorException("Respuesta JSON inválida de Koha al buscar patrones. URL: " + fullUrl + ", Response: " + response, e);
                }
            }

            for (int i = 0; i < pageResults.length(); i++) {
                try {
                    JSONObject patron = pageResults.getJSONObject(i);
                    if (!consumer.test(patron)) {
                        break outer;
                    }
                    totalDelivered++;
                } catch (JSONException e) {
                    throw new ConnectorException("Error processing individual patron from search results. URL: " + fullUrl + ", Entry: " + pageResults.opt(i), e);
                }
            }

            if (httpResult.getTotalCount() != null) {
                moreResults = totalDelivered < httpResult.getTotalCount();
            } else {
                moreResults = pageResults.length() == pageSize;
            }
            pageCount++;
            if (pageCount >= MAX_PAGES) {
                LOG.warn("Max pages limit ({0}) reached, stopping pagination. Results may be incomplete.", MAX_PAGES);
                break;
            }
            if (moreResults) currentPage++;

        } while (moreResults);

        return totalDelivered;
    }
}
