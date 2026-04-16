package com.identicum.connectors.services;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPatch;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpRequestBase;
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
        KohaConfiguration configuration = new KohaConfiguration();
        patronService = new PatronService(httpClient, "http://localhost", configuration);
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
        when(httpClient.execute(any(HttpPatch.class))).thenReturn(resp);

        JSONObject payload = new JSONObject().put("email", "a@b.com");
        assertDoesNotThrow(() -> patronService.updatePatron("1", payload));
    }

    @Test
    void testGetPatronSendsXKohaEmbedHeader() throws Exception {
        CloseableHttpResponse resp = prepareResponse(200, "{\"patron_id\":1,\"userid\":\"jdoe\"}");
        org.apache.http.client.methods.HttpGet[] capturedRequest = new org.apache.http.client.methods.HttpGet[1];
        when(httpClient.execute(any(HttpGet.class))).thenAnswer(invocation -> {
            capturedRequest[0] = invocation.getArgument(0);
            return resp;
        });

        patronService.getPatron("1");

        assertNotNull(capturedRequest[0], "Request should have been captured");
        org.apache.http.Header embedHeader = capturedRequest[0].getFirstHeader("x-koha-embed");
        assertNotNull(embedHeader, "x-koha-embed header should be present on GET patron");
        assertEquals("extended_attributes", embedHeader.getValue());
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

        java.util.List<JSONObject> result = new java.util.ArrayList<>();
        patronService.searchPatrons(null, new OperationOptionsBuilder().setPageSize(2).build(), patron -> { result.add(patron); return true; });
        assertEquals(3, result.size());
    }

    // --- Casos de error HTTP para getPatron ---

    @Test
    void testGetPatron_400_throwsInvalidAttributeValueException() throws Exception {
        CloseableHttpResponse resp = prepareResponse(400, "{\"error\":\"Bad Request\"}");
        when(httpClient.execute(any(HttpGet.class))).thenReturn(resp);
        assertThrows(InvalidAttributeValueException.class, () -> patronService.getPatron("1"));
    }

    @Test
    void testGetPatron_401_throwsPermissionDeniedException() throws Exception {
        CloseableHttpResponse resp = prepareResponse(401, "{\"error\":\"Unauthorized\"}");
        when(httpClient.execute(any(HttpGet.class))).thenReturn(resp);
        assertThrows(PermissionDeniedException.class, () -> patronService.getPatron("1"));
    }

    @Test
    void testGetPatron_403_throwsPermissionDeniedException() throws Exception {
        CloseableHttpResponse resp = prepareResponse(403, "{\"error\":\"Forbidden\"}");
        when(httpClient.execute(any(HttpGet.class))).thenReturn(resp);
        assertThrows(PermissionDeniedException.class, () -> patronService.getPatron("1"));
    }

    @Test
    void testGetPatron_404_throwsUnknownUidException() throws Exception {
        CloseableHttpResponse resp = prepareResponse(404, "{\"error\":\"Not Found\"}");
        when(httpClient.execute(any(HttpGet.class))).thenReturn(resp);
        assertThrows(UnknownUidException.class, () -> patronService.getPatron("1"));
    }

    @Test
    void testGetPatron_409_throwsAlreadyExistsException() throws Exception {
        CloseableHttpResponse resp = prepareResponse(409, "{\"error\":\"Conflict\"}");
        when(httpClient.execute(any(HttpGet.class))).thenReturn(resp);
        assertThrows(AlreadyExistsException.class, () -> patronService.getPatron("1"));
    }

    @Test
    void testGetPatron_500_throwsConnectionFailedException() throws Exception {
        CloseableHttpResponse resp = prepareResponse(500, "{\"error\":\"Internal Server Error\"}");
        when(httpClient.execute(any(HttpGet.class))).thenReturn(resp);
        assertThrows(ConnectionFailedException.class, () -> patronService.getPatron("1"));
    }

    // --- Casos de error HTTP para createPatron ---

    @Test
    void testCreatePatron_400_throwsInvalidAttributeValueException() throws Exception {
        CloseableHttpResponse resp = prepareResponse(400, "{\"error\":\"Bad Request\"}");
        when(httpClient.execute(any(HttpPost.class))).thenReturn(resp);
        JSONObject payload = new JSONObject().put("userid", "newuser");
        assertThrows(InvalidAttributeValueException.class, () -> patronService.createPatron(payload));
    }

    @Test
    void testCreatePatron_401_throwsPermissionDeniedException() throws Exception {
        CloseableHttpResponse resp = prepareResponse(401, "{\"error\":\"Unauthorized\"}");
        when(httpClient.execute(any(HttpPost.class))).thenReturn(resp);
        JSONObject payload = new JSONObject().put("userid", "newuser");
        assertThrows(PermissionDeniedException.class, () -> patronService.createPatron(payload));
    }

    @Test
    void testCreatePatron_403_throwsPermissionDeniedException() throws Exception {
        CloseableHttpResponse resp = prepareResponse(403, "{\"error\":\"Forbidden\"}");
        when(httpClient.execute(any(HttpPost.class))).thenReturn(resp);
        JSONObject payload = new JSONObject().put("userid", "newuser");
        assertThrows(PermissionDeniedException.class, () -> patronService.createPatron(payload));
    }

    @Test
    void testCreatePatron_409_throwsAlreadyExistsException() throws Exception {
        CloseableHttpResponse resp = prepareResponse(409, "{\"error\":\"Conflict\"}");
        when(httpClient.execute(any(HttpPost.class))).thenReturn(resp);
        JSONObject payload = new JSONObject().put("userid", "existing");
        assertThrows(AlreadyExistsException.class, () -> patronService.createPatron(payload));
    }

    @Test
    void testCreatePatron_500_throwsConnectionFailedException() throws Exception {
        CloseableHttpResponse resp = prepareResponse(500, "{\"error\":\"Internal Server Error\"}");
        when(httpClient.execute(any(HttpPost.class))).thenReturn(resp);
        JSONObject payload = new JSONObject().put("userid", "newuser");
        assertThrows(ConnectionFailedException.class, () -> patronService.createPatron(payload));
    }
}
