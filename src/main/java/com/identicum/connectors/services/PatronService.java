package com.identicum.connectors.services;

import com.identicum.connectors.KohaConfiguration;
import com.identicum.connectors.KohaFilter;
import com.identicum.connectors.mappers.PatronMapper;
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
     * Crea un patron en Koha. Si Koha devuelve 409 Conflict, hace la operacion
     * IDEMPOTENTE (create-or-adopt): resuelve el borrower existente y devuelve su
     * identidad como si el create lo hubiera "adoptado", para que MidPoint linkee
     * el shadow al borrower existente en UN paso, sin disparar el bucle de reintentos.
     *
     * Koha devuelve 409 ("A patron record matching these details already exists")
     * cuando colisiona CUALQUIER campo unico: cardnumber, userid O email
     * (borrowers.email tiene constraint unico en esta instancia). Por eso se intenta
     * la adopcion idempotente por cada uno de esos campos, en ese orden. Con arranque
     * de cero y cardnumber=ID_PERSONA (unico e inmutable) la correlacion por cardnumber
     * es la via principal; userid y email cubren colisiones legitimas de campos unicos.
     *
     * Todas las busquedas usan _match=exact para garantizar adopcion 1:1 y evitar
     * adoptar al borrower equivocado por coincidencia parcial (Koha por defecto hace
     * match difuso tipo "contains").
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
                JSONObject existing = findPatronExact("cardnumber", cardnumber);
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
                JSONObject existing = findPatronExact("userid", userid);
                if (existing != null && existing.has("patron_id")) {
                    LOG.ok("Patron encontrado via userid={0}, patron_id={1}. Operacion idempotente.",
                            userid, existing.get("patron_id"));
                    return existing;
                }
                LOG.info("No encontrado por userid={0}. Intentando fallback por email.", userid);
            }

            // Fallback final REST: buscar por email institucional.
            // En el universo de huerfanos UPeU, el email es frecuentemente el UNICO
            // identificador compartido entre el borrower legacy y el focus canonico.
            String email = payload.optString("email", null);
            if (email != null && !email.isEmpty()) {
                LOG.info("CREATE patron 409: buscando por email={0}.", email);
                JSONObject existing = findPatronExact("email", email);
                if (existing != null && existing.has("patron_id")) {
                    LOG.ok("Patron encontrado via email={0}, patron_id={1}. Operacion idempotente.",
                            email, existing.get("patron_id"));
                    return existing;
                }
                LOG.warn("No encontrado por email={0} tampoco.", email);
            }

            LOG.warn("CREATE patron 409: no se pudo recuperar patron existente por cardnumber, userid ni email. Relanzando.");
            throw e;
        }
    }

    /**
     * Busca un patron por un campo indexado de Koha (cardnumber|userid|email) con
     * coincidencia EXACTA. Devuelve el primer resultado o null si no existe.
     *
     * Se fuerza _match=exact porque la API Koha por defecto aplica match difuso
     * ("contains"): sin exact, buscar email="a@x" podria devolver "aa@x", lo que en
     * el contexto de adopcion idempotente significaria linkear al borrower equivocado.
     */
    private JSONObject findPatronExact(String field, String value) throws ConnectorException, IOException {
        String url = getBaseUrl() + "?" + field + "=" + urlEncodeUTF8(value) + "&_match=exact&_per_page=1&_page=1";
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
            LOG.warn("No se pudo parsear la respuesta al buscar patron por {0}={1}: {2}", field, value, responseBody);
            return null;
        }
    }

    /**
     * Actualiza un patron en Koha via PUT (full-replace).
     *
     * <p>La API Koha PUT requiere todos los campos OBLIGATORIOS (library_id, surname,
     * cardnumber, category_id, userid) aunque solo haya cambiado un campo. Por eso hacemos
     * GET primero (para arrastrar los obligatorios), mergeamos el delta encima, y enviamos
     * el objeto resultante.</p>
     *
     * <p>FILTRADO POR ALLOWLIST (v1.3.12): el schema patron.yaml de Koha declara
     * {@code additionalProperties: false}; ademas el GET devuelve campos CALCULADOS que NO
     * pertenecen al schema escribible (p.ej. {@code self_renewal_available},
     * {@code check_previous_checkout}, contadores, etc.). Reenviar CUALQUIER campo ajeno al
     * schema escribible provoca {@code Koha::Exceptions::Object::PropertyNotFound} -> HTTP 500.
     * Por eso el body del PUT se reduce a la ALLOWLIST de campos que el conector sabe que son
     * escribibles ({@link PatronMapper#getWritablePatronNativeFields()}). Esto reemplaza al
     * antiguo enfoque de DENYLIST (read-only fields), fragil ante nuevos campos calculados
     * que Koha agregue al GET en cada version.</p>
     *
     * <p>Los valores del delta entrante ({@code payload}) que correspondan a un campo
     * escribible se aplican aunque el GET no los tuviera; los campos de solo lectura nunca
     * se envian, vengan del GET o del delta.</p>
     */
    public void updatePatron(String uid, JSONObject payload) throws ConnectorException, IOException {
        // GET estado actual para arrastrar los campos OBLIGATORIOS del PUT.
        JSONObject current = getPatronBasic(uid);
        // Overlay: los nuevos valores sobreescriben los actuales.
        for (String key : payload.keySet()) {
            current.put(key, payload.get(key));
        }
        // Construir el body SOLO con la allowlist de campos escribibles conocidos.
        // Cualquier campo calculado/no-schema (self_renewal_available, etc.) queda fuera.
        java.util.Set<String> writable = PatronMapper.getWritablePatronNativeFields();
        JSONObject body = new JSONObject();
        for (String key : writable) {
            if (current.has(key)) {
                body.put(key, current.get(key));
            }
        }
        HttpPut request = new HttpPut(getBaseUrl() + "/" + uid);
        callRequestWithEntity(request, body);
    }

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

    /**
     * Escribe los extended_attributes de un patron EXISTENTE via el endpoint dedicado
     * de Koha {@code PUT /patrons/{id}/extended_attributes} (overwrite-all).
     *
     * <p>MOTIVO (v1.3.11): la API REST de Koha NO acepta {@code extended_attributes}
     * en el cuerpo del {@code PUT /patrons/{id}} (por eso esta en
     * {@link #READ_ONLY_PATRON_FIELDS} y se elimina del PUT principal). Los atributos
     * extendidos SOLO se gestionan por su endpoint dedicado. Hasta v1.3.10 el conector
     * NUNCA los escribia en UPDATE -> los borrowers existentes jamas recibian STUDYCYCLE
     * ni ningun otro extended_attribute por reconcile.</p>
     *
     * <p>SEMANTICA merge-preserve (gobierno IGA): el endpoint PUT de Koha es
     * overwrite-all (reemplaza TODOS los extended_attributes del patron). Para respetar
     * el modelo de gobierno de MidPoint (tolerant=true + intolerantValuePattern) sin
     * destruir atributos tolerados que MidPoint no envia, se construye el set final asi:</p>
     * <ol>
     *   <li>GET de los extended_attributes ACTUALES del patron.</li>
     *   <li>Se PRESERVAN las entradas actuales cuyo {@code type} NO aparece en el
     *       conjunto de valores deseados que envia MidPoint (atributos tolerados ajenos
     *       al gobierno de este flujo).</li>
     *   <li>Se REEMPLAZAN por completo las entradas de los {@code type} que MidPoint
     *       SI envia (gobierno autoritativo de STUDYCYCLE/STUDY_LEVEL/AREA/etc.).</li>
     * </ol>
     * <p>Asi, un type gobernado se sincroniza 1:1 con lo que computa MidPoint (incluye
     * el caso de removerlo: si MidPoint deja de enviar un type que antes enviaba, ese
     * type se vacia porque ya no esta en el set deseado y sus entradas no se preservan).
     * Un type ajeno (no enviado nunca) se conserva intacto.</p>
     *
     * <p>El formato de cada entrada enviada a Koha es {@code {"type":X,"value":Y}}.
     * Compatible con {@code convertExtendedAttributesToKoha}.</p>
     *
     * @param uid            patron_id (borrowernumber)
     * @param desiredEntries valores deseados que envia MidPoint, como JSONArray de
     *                       objetos {@code {"type":...,"value":...}} (puede ser vacio)
     */
    public void replaceExtendedAttributes(String uid, JSONArray desiredEntries)
            throws ConnectorException, IOException {
        if (desiredEntries == null) {
            desiredEntries = new JSONArray();
        }

        // 1) Conjunto de types gobernados = los que MidPoint envia en este UPDATE.
        java.util.Set<String> governedTypes = new java.util.HashSet<>();
        for (int i = 0; i < desiredEntries.length(); i++) {
            JSONObject e = desiredEntries.optJSONObject(i);
            if (e != null) {
                String t = e.optString("type", null);
                if (t != null && !t.isEmpty()) {
                    governedTypes.add(t);
                }
            }
        }

        // 2) GET actuales y preservar los types NO gobernados (tolerados ajenos al flujo).
        JSONArray finalSet = new JSONArray();
        JSONObject current = getPatron(uid);
        Object rawCurrent = current.opt("extended_attributes");
        if (rawCurrent instanceof JSONArray) {
            JSONArray currentArr = (JSONArray) rawCurrent;
            for (int i = 0; i < currentArr.length(); i++) {
                JSONObject e = currentArr.optJSONObject(i);
                if (e == null) continue;
                String t = e.optString("type", null);
                if (t == null || t.isEmpty()) continue;
                if (!governedTypes.contains(t)) {
                    // Type ajeno al gobierno de MidPoint: preservar tal cual,
                    // re-normalizando a {type,value} (Koha PUT no acepta extended_attribute_id).
                    JSONObject preserved = new JSONObject();
                    preserved.put("type", t);
                    preserved.put("value", e.optString("value", ""));
                    finalSet.put(preserved);
                }
            }
        }

        // 3) Agregar los valores deseados (gobierno autoritativo de sus types).
        for (int i = 0; i < desiredEntries.length(); i++) {
            JSONObject e = desiredEntries.optJSONObject(i);
            if (e == null) continue;
            String t = e.optString("type", null);
            if (t == null || t.isEmpty()) continue;
            JSONObject entry = new JSONObject();
            entry.put("type", t);
            entry.put("value", e.optString("value", ""));
            finalSet.put(entry);
        }

        // 4) PUT overwrite-all al endpoint dedicado. El cuerpo es el ARRAY directo.
        HttpPut request = new HttpPut(getBaseUrl() + "/" + uid + "/extended_attributes");
        LOG.ok("PUT extended_attributes patron {0}: {1} entradas (governedTypes={2})",
                uid, finalSet.length(), governedTypes);
        callRequestWithArrayEntity(request, finalSet);
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
