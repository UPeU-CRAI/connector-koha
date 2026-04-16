package com.identicum.connectors.services;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import com.identicum.connectors.KohaConfiguration;
import com.identicum.connectors.services.HttpClientAdapter;
import org.apache.http.StatusLine;
import org.apache.http.HttpEntity;
import org.identityconnectors.framework.common.exceptions.AlreadyExistsException;
import org.identityconnectors.framework.common.exceptions.ConnectionFailedException;
import org.identityconnectors.framework.common.exceptions.InvalidAttributeValueException;
import org.identityconnectors.framework.common.exceptions.PermissionDeniedException;
import org.identityconnectors.framework.common.exceptions.UnknownUidException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.identityconnectors.framework.common.objects.OperationOptionsBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class CategoryServiceTest {

    @Mock
    private HttpClientAdapter httpClient;

    private CategoryService categoryService;

    @BeforeEach
    void setUp() {
        KohaConfiguration configuration = new KohaConfiguration();
        categoryService = new CategoryService(httpClient, "http://localhost", configuration);
    }

    private CloseableHttpResponse prepareResponse(int status, String body) throws IOException {
        CloseableHttpResponse response = mock(CloseableHttpResponse.class);
        StatusLine statusLine = mock(StatusLine.class);
        when(statusLine.getStatusCode()).thenReturn(status);
        when(response.getStatusLine()).thenReturn(statusLine);
        HttpEntity entity = body == null ? null : new StringEntity(body, ContentType.APPLICATION_JSON);
        when(response.getEntity()).thenReturn(entity);
        return response;
    }

    @Test
    void testGetCategorySuccess() throws Exception {
        CloseableHttpResponse resp = prepareResponse(200, "{\"patron_category_id\":\"C1\"}");
        when(httpClient.execute(any(HttpGet.class))).thenReturn(resp);

        JSONObject obj = categoryService.getCategory("C1");
        assertEquals("C1", obj.getString("patron_category_id"));
    }

    @Test
    void testSearchCategoriesPagination() throws Exception {
        JSONArray page1 = new JSONArray()
                .put(new JSONObject().put("patron_category_id", "C1"))
                .put(new JSONObject().put("patron_category_id", "C2"));
        JSONArray page2 = new JSONArray().put(new JSONObject().put("patron_category_id", "C3"));
        CloseableHttpResponse resp1 = prepareResponse(200, page1.toString());
        CloseableHttpResponse resp2 = prepareResponse(200, page2.toString());
        when(httpClient.execute(any(HttpGet.class))).thenReturn(resp1, resp2);

        JSONArray result = categoryService.searchCategories(null, new OperationOptionsBuilder().setPageSize(2).build());
        assertEquals(3, result.length());
    }

    // --- Casos de error HTTP para getCategory ---

    @Test
    void testGetCategory_400_throwsInvalidAttributeValueException() throws Exception {
        CloseableHttpResponse resp = prepareResponse(400, "{\"error\":\"Bad Request\"}");
        when(httpClient.execute(any(HttpGet.class))).thenReturn(resp);
        assertThrows(InvalidAttributeValueException.class, () -> categoryService.getCategory("C1"));
    }

    @Test
    void testGetCategory_401_throwsPermissionDeniedException() throws Exception {
        CloseableHttpResponse resp = prepareResponse(401, "{\"error\":\"Unauthorized\"}");
        when(httpClient.execute(any(HttpGet.class))).thenReturn(resp);
        assertThrows(PermissionDeniedException.class, () -> categoryService.getCategory("C1"));
    }

    @Test
    void testGetCategory_403_throwsPermissionDeniedException() throws Exception {
        CloseableHttpResponse resp = prepareResponse(403, "{\"error\":\"Forbidden\"}");
        when(httpClient.execute(any(HttpGet.class))).thenReturn(resp);
        assertThrows(PermissionDeniedException.class, () -> categoryService.getCategory("C1"));
    }

    @Test
    void testGetCategory_404_throwsUnknownUidException() throws Exception {
        CloseableHttpResponse resp = prepareResponse(404, "{\"error\":\"Not Found\"}");
        when(httpClient.execute(any(HttpGet.class))).thenReturn(resp);
        assertThrows(UnknownUidException.class, () -> categoryService.getCategory("C1"));
    }

    @Test
    void testGetCategory_409_throwsAlreadyExistsException() throws Exception {
        CloseableHttpResponse resp = prepareResponse(409, "{\"error\":\"Conflict\"}");
        when(httpClient.execute(any(HttpGet.class))).thenReturn(resp);
        assertThrows(AlreadyExistsException.class, () -> categoryService.getCategory("C1"));
    }

    @Test
    void testGetCategory_500_throwsConnectionFailedException() throws Exception {
        CloseableHttpResponse resp = prepareResponse(500, "{\"error\":\"Internal Server Error\"}");
        when(httpClient.execute(any(HttpGet.class))).thenReturn(resp);
        assertThrows(ConnectionFailedException.class, () -> categoryService.getCategory("C1"));
    }

}
