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
     * Crea un patron en Koha. Si Koha devuelve 409 Conflict, busca el patron
     * existente primero por cardnumber, luego por userid (fallback).
     * Hace la operacion idempotente para shadow muerta/perdida en MidPoint.
     *
     * Koha puede devolver 409 por conflicto en cardnumber O en userid.
     * Por eso se intentan ambas busquedas antes de relanzar la excepcion.
     */
    public JSONObject createPatron(JSONObject payload) throws ConnectorException, IOException {
        HttpPost request = new HttpPost(getBaseUrl());
        try {
            return callRequestWithEntity(request, payload);
        } catch (AlreadyExistsException e) {
            // Koha devolvio 409. Intentar recuperar por cardnumber primero.
            String cardnumber = payload.optString("cardnumber", null);
            if (cardnumber != null && !cardnumber.isEmpty()) {
                LOG.info("CREATE patron 409: buscando por cardnumber={0}.", cardnumber);
                JSONObject existing = findPatronByCardnumber(cardnumber);
                if (existing != null && existing.has("patron_id")) {
                    LOG.ok("Patron encontrado via cardnumber={0}, patron_id={1}. Operacion idempotente.",
                            cardnumber, existing.get("patron_id"));
                    return existing;
                }
                LOG.info("No encontrado por cardnumber={0}. Intentando fallback por userid.", cardnumber);
            } else {
                LOG.warn("CREATE patron devolvio 409 pero el payload no contiene cardnumber. Intentando por userid.");
            }

            // Fallback: buscar por userid (el conflicto puede ser en ese campo).
            String userid = payload.optString("userid", null);
            if (userid != null && !userid.isEmpty()) {
                LOG.info("CREATE patron 409: buscando por userid={0}.", userid);
                JSONObject existing = findPatronByUserid(userid);
                if (existing != null && existing.has("patron_id")) {
                    LOG.ok("Patron encontrado via userid={0}, patron_id={1}. Operacion idempotente.",
                            userid, existing.get("patron_id"));
                    return existing;
                }
                LOG.warn("No encontrado por userid={0} tampoco.", userid);
            }

            LOG.warn("CREATE patron 409: no se pudo recuperar patron existente ni por cardnumber ni por userid. Relanzando.");
            throw e;
        }
    }

    /**
     * Busca un patron por userid. Devuelve el primer resultado o null si no existe.
     */
    private JSONObject findPatronByUserid(String userid) throws ConnectorException, IOException {
        String url = getBaseUrl() + "?userid=" + urlEncodeUTF8(userid) + "&_per_page=1&_page=1";
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
            LOG.warn("No se pudo parsear la respuesta al buscar patron por userid={0}: {1}", userid, responseBody);
            return null;
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

    /**
     * Actualiza un patron en Koha via PUT (full-replace).
     * La API Koha PUT requiere todos los campos obligatorios (library_id, surname, etc.)
     * aunque solo haya cambiado un campo. Por eso hacemos GET primero, mergeamos
     * el delta encima, y luego enviamos el objeto completo.
     */
    public void updatePatron(String uid, JSONObject payload) throws ConnectorException, IOException {
        // GET estado actual para construir body PUT completo
        JSONObject current = getPatronBasic(uid);
        // Overlay: los nuevos valores sobreescriben los actuales
        for (String key : payload.keySet()) {
            current.put(key, payload.get(key));
        }
        // Eliminar campos de solo-lectura que Koha rechazaria en el PUT
        for (String readOnly : READ_ONLY_PATRON_FIELDS) {
            current.remove(readOnly);
        }
        HttpPut request = new HttpPut(getBaseUrl() + "/" + uid);
        callRequestWithEntity(request, current);
    }

    /**
     * Campos que Koha calcula internamente y no acepta en PUT.
     * Si un campo del GET se incluye en el PUT y es read-only, Koha devuelve 400.
     * Lista expandida tras analizar respuestas 400 en PROD.
     */
    private static final String[] READ_ONLY_PATRON_FIELDS = {
        "patron_id",            // PK, asignado por Koha
        "anonymized",           // gestionado por procesos de privacidad
        "expired",              // calculado: expiry_date < today
        "restricted",           // calculado: debarred activo
        "last_seen",            // auto-actualizado en login
        "updated_on",           // timestamp auto-actualizado
        "date_renewed",         // auto-actualizado en renovación
        "extended_attributes",  // endpoint separado /extended_attributes
        "overdrive_auth_token", // token externo, read-only
        "primary_contact_method", // calculado
        "checkouts_count",      // calculado
        "overdues_count",       // calculado
        "holds_count",          // calculado
        "account_balance"       // calculado
    };

    /** GET basico de patron sin embed de extended_attributes (para uso interno en updatePatron). */
    private JSONObject getPatronBasic(String uid) throws ConnectorException, IOException {
        HttpGet request = new HttpGet(getBaseUrl() + "/" + uid);
        String responseBody = callRequest(request);
        try {
            if (StringUtil.isBlank(responseBody)) {
                return new JSONObject();
            }
            return new JSONObject(responseBody);
        } catch (JSONException e) {
            throw new ConnectorException("Error parseando patron UID " + uid + ": " + responseBody, e);
        }
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
