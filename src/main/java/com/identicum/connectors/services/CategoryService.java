package com.identicum.connectors.services;

import com.identicum.connectors.KohaFilter;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import com.identicum.connectors.services.HttpClientAdapter;
import org.identityconnectors.common.StringUtil;
import org.identityconnectors.common.logging.Log;
import org.identityconnectors.framework.common.exceptions.ConnectorException;
// ConnectorRuntimeException is imported by AbstractKohaService's wildcard import if needed
import org.identityconnectors.framework.common.objects.OperationOptions;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays; // Keep this if it's used, though current refactoring might remove its direct use.
import java.util.List;

/**
 * Servicio para gestionar las operaciones CRUD y de búsqueda para las Categorías de Patrones de Koha.
 */
public class CategoryService extends AbstractKohaService {

    private static final Log LOG = Log.getLog(CategoryService.class);
    // API_BASE_PATH and ENDPOINT are handled by AbstractKohaService now

    public CategoryService(HttpClientAdapter httpClient, String serviceAddress) {
        super(httpClient, serviceAddress);
    }

    @Override
    protected String getEndpoint() {
        return "/patron_categories";
    }

    @Override
    protected String getResourceName() {
        return "category";
    }

    // getBaseUrl() is inherited

    public JSONObject getCategory(String uid) throws ConnectorException, IOException {
        HttpGet request = new HttpGet(getBaseUrl() + "/" + uid);
        String responseBody = callRequest(request); // Inherited
        try {
            if (StringUtil.isBlank(responseBody)) {
                 // Consistent with getPatron, if callRequest returns empty for 200/204.
                return new JSONObject();
            }
            return new JSONObject(responseBody);
        } catch (JSONException e) {
            throw new ConnectorException("Failed to parse JSON response for getCategory UID " + uid + ". Response: " + responseBody, e);
        }
    }

    public JSONObject createCategory(JSONObject payload) throws ConnectorException, IOException {
        HttpPost request = new HttpPost(getBaseUrl());
        return callRequestWithEntity(request, payload); // Inherited
    }

    public void updateCategory(String uid, JSONObject payload) throws ConnectorException, IOException {
        HttpPut request = new HttpPut(getBaseUrl() + "/" + uid);
        callRequestWithEntity(request, payload); // Inherited, ignore return for void
    }

    public void deleteCategory(String uid) throws ConnectorException, IOException {
        HttpDelete request = new HttpDelete(getBaseUrl() + "/" + uid);
        callRequest(request); // Inherited
    }

    public JSONArray searchCategories(KohaFilter filter, OperationOptions opts) throws ConnectorException, IOException {
        JSONArray allResults = new JSONArray();
        int pageSize = (opts != null && opts.getPageSize() != null) ? opts.getPageSize() : 100;
        int currentPage = 1;
        boolean moreResults;
        String fullUrl = ""; // For logging

        do {
            List<String> queryParams = new ArrayList<>();
            queryParams.add("_per_page=" + pageSize);
            queryParams.add("_page=" + currentPage);

            if (filter != null && StringUtil.isNotBlank(filter.getByName())) {
                // For categories, Koha typically filters by 'description' for the name/description field
                queryParams.add("description=" + urlEncodeUTF8(filter.getByName())); // urlEncodeUTF8 inherited
            }

            fullUrl = getBaseUrl() + "?" + String.join("&", queryParams);
            HttpGet request = new HttpGet(fullUrl);
            LOG.info("CATEGORY_SEARCH: URL: {0}", request.getURI()); // Changed from LOG.ok

            String response = callRequest(request); // Inherited
            JSONArray pageResults;

            if (StringUtil.isBlank(response)) {
                pageResults = new JSONArray();
            } else {
                try {
                    // Koha's category search usually returns a direct array.
                    // However, to be safe and consistent with PatronService's potential single object response:
                    if (response.trim().startsWith("{")) {
                         JSONObject responseObject = new JSONObject(response);
                         // Check if the response has a "patron_categories" array, common in some paginated Koha responses
                         if (responseObject.has("patron_categories") && responseObject.get("patron_categories") instanceof JSONArray) {
                            pageResults = responseObject.getJSONArray("patron_categories");
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
                     throw new ConnectorException("Respuesta JSON inválida de Koha al buscar categorías. URL: " + fullUrl + ", Response: " + response, e);
                }
            }

            if (pageResults.length() > 0) {
                for (int i = 0; i < pageResults.length(); i++) {
                     try {
                        allResults.put(pageResults.getJSONObject(i));
                    } catch (JSONException e) {
                         throw new ConnectorException("Error processing individual category from search results. URL: " + fullUrl + ", Entry: " + pageResults.opt(i), e);
                    }
                }
            }

            moreResults = pageResults.length() == pageSize;
            if (moreResults) {
                currentPage++;
            }

        } while (moreResults);

        return allResults;
    }

}
