package com.identicum.connectors.services;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import com.identicum.connectors.services.HttpClientAdapter;
import org.apache.http.StatusLine;
import org.apache.http.HttpEntity;
import org.json.JSONArray;
import org.json.JSONObject;
import org.identityconnectors.framework.common.objects.OperationOptionsBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class PatronServiceTest {

    @Mock
    private HttpClientAdapter httpClient;

    private PatronService patronService;

    @BeforeEach
    void setUp() {
        patronService = new PatronService(httpClient, "http://localhost");
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
    void testGetPatronSuccess() throws Exception {
        CloseableHttpResponse resp = prepareResponse(200, "{\"patron_id\":1,\"userid\":\"jdoe\"}");
        when(httpClient.execute(any(HttpGet.class))).thenReturn(resp);

        JSONObject obj = patronService.getPatron("1");
        assertEquals(1, obj.getInt("patron_id"));
        assertEquals("jdoe", obj.getString("userid"));
    }

    @Test
    void testCreatePatronSuccess() throws Exception {
        CloseableHttpResponse resp = prepareResponse(200, "{\"patron_id\":2}");
        when(httpClient.execute(any(HttpPost.class))).thenReturn(resp);

        JSONObject payload = new JSONObject().put("userid", "newuser");
        JSONObject created = patronService.createPatron(payload);
        assertEquals(2, created.getInt("patron_id"));
    }

    @Test
    void testUpdatePatronSuccess() throws Exception {
        CloseableHttpResponse resp = prepareResponse(200, "{}");
        when(httpClient.execute(any(HttpPut.class))).thenReturn(resp);

        JSONObject payload = new JSONObject().put("email", "a@b.com");
        assertDoesNotThrow(() -> patronService.updatePatron("1", payload));
    }

    @Test
    void testDeletePatronSuccess() throws Exception {
        CloseableHttpResponse resp = prepareResponse(204, null);
        when(httpClient.execute(any(HttpDelete.class))).thenReturn(resp);

        assertDoesNotThrow(() -> patronService.deletePatron("1"));
    }

    @Test
    void testSearchPatronsPagination() throws Exception {
        JSONArray page1 = new JSONArray()
                .put(new JSONObject().put("patron_id", 1))
                .put(new JSONObject().put("patron_id", 2));
        JSONArray page2 = new JSONArray().put(new JSONObject().put("patron_id", 3));
        CloseableHttpResponse resp1 = prepareResponse(200, page1.toString());
        CloseableHttpResponse resp2 = prepareResponse(200, page2.toString());
        when(httpClient.execute(any(HttpGet.class))).thenReturn(resp1, resp2);

        JSONArray result = patronService.searchPatrons(null, new OperationOptionsBuilder().setPageSize(2).build());
        assertEquals(3, result.length());
    }
}
