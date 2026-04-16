package com.identicum.connectors.services;

import com.identicum.connectors.KohaConfiguration;
import com.identicum.connectors.KohaFilter;
import com.identicum.connectors.services.HttpClientAdapter;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPatch;
import org.identityconnectors.common.StringUtil;
import org.identityconnectors.common.logging.Log;
import org.identityconnectors.framework.common.exceptions.ConnectorException;
import org.identityconnectors.framework.common.objects.OperationOptions;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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

    public JSONObject createPatron(JSONObject payload) throws ConnectorException, IOException {
        HttpPost request = new HttpPost(getBaseUrl());
        return callRequestWithEntity(request, payload);
    }

    public void updatePatron(String uid, JSONObject payload) throws ConnectorException, IOException {
        HttpPatch request = new HttpPatch(getBaseUrl() + "/" + uid);
        callRequestWithEntity(request, payload);
    }

    public void deletePatron(String uid) throws ConnectorException, IOException {
        HttpDelete request = new HttpDelete(getBaseUrl() + "/" + uid);
        callRequest(request);
    }

    public JSONArray searchPatrons(KohaFilter filter, OperationOptions opts) throws ConnectorException, IOException {
        JSONArray allResults = new JSONArray();
        int pageSize = (opts != null && opts.getPageSize() != null) ? opts.getPageSize() : configuration.getPageSize();
        int currentPage = 1;
        int pageCount = 0;
        final int MAX_PAGES = 1000;
        boolean moreResults;
        String fullUrl;

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
                    allResults.put(pageResults.getJSONObject(i));
                } catch (JSONException e) {
                    throw new ConnectorException("Error processing individual patron from search results. URL: " + fullUrl + ", Entry: " + pageResults.opt(i), e);
                }
            }

            if (httpResult.getTotalCount() != null) {
                moreResults = allResults.length() < httpResult.getTotalCount();
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

        return allResults;
    }
}
