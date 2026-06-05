package com.identicum.connectors.services;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPatch;
import org.apache.http.client.methods.HttpPut;
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
        // updatePatron hace GET (getPatronBasic) + merge del delta + PUT full-replace.
        CloseableHttpResponse getResp = prepareResponse(200, "{\"patron_id\":1,\"userid\":\"jdoe\",\"surname\":\"Doe\",\"library_id\":\"MAIN\"}");
        CloseableHttpResponse putResp = prepareResponse(200, "{}");
        when(httpClient.execute(any(HttpGet.class))).thenReturn(getResp);
        when(httpClient.execute(any(HttpPut.class))).thenReturn(putResp);

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
    void testCreatePatron_409_noMatch_throwsAlreadyExistsException() throws Exception {
        // POST -> 409 ; todas las busquedas de adopt (GET) devuelven array vacio -> relanza 409.
        CloseableHttpResponse postResp = prepareResponse(409, "{\"error\":\"A patron record matching these details already exists\"}");
        CloseableHttpResponse getResp = prepareResponse(200, "[]");
        when(httpClient.execute(any(HttpPost.class))).thenReturn(postResp);
        when(httpClient.execute(any(HttpGet.class))).thenReturn(getResp);
        JSONObject payload = new JSONObject().put("userid", "existing").put("email", "ghost@upeu.edu.pe");
        assertThrows(AlreadyExistsException.class, () -> patronService.createPatron(payload));
    }

    @Test
    void testCreatePatron_409_adoptByCardnumber() throws Exception {
        // POST -> 409 ; GET por cardnumber devuelve el borrower existente -> adopta (idempotente).
        CloseableHttpResponse postResp = prepareResponse(409, "{\"error\":\"A patron record matching these details already exists\"}");
        CloseableHttpResponse getResp = prepareResponse(200, "[{\"patron_id\":777,\"cardnumber\":\"202612131\",\"userid\":\"maria.pompa\"}]");
        when(httpClient.execute(any(HttpPost.class))).thenReturn(postResp);
        when(httpClient.execute(any(HttpGet.class))).thenReturn(getResp);
        JSONObject payload = new JSONObject().put("cardnumber", "202612131").put("userid", "202612131");
        JSONObject adopted = patronService.createPatron(payload);
        assertEquals(777, adopted.getInt("patron_id"));
    }

    @Test
    void testCreatePatron_409_adoptByEmail() throws Exception {
        // Caso real del storm: cardnumber/userid CANONICOS no matchean al borrower legacy,
        // solo el email institucional lo hace. POST -> 409 ; GET cardnumber -> [] ; GET userid -> [] ;
        // GET email -> borrower existente -> adopta por email.
        CloseableHttpResponse postResp = prepareResponse(409, "{\"error\":\"A patron record matching these details already exists\"}");
        CloseableHttpResponse emptyResp = prepareResponse(200, "[]");
        CloseableHttpResponse emailResp = prepareResponse(200, "[{\"patron_id\":1944,\"cardnumber\":\"45788343\",\"userid\":\"robertoestrada\",\"email\":\"robertoestrada@upeu.edu.pe\"}]");
        when(httpClient.execute(any(HttpPost.class))).thenReturn(postResp);
        // El primer y segundo GET (cardnumber, userid) -> vacios ; el tercero (email) -> match.
        when(httpClient.execute(any(HttpGet.class)))
                .thenReturn(emptyResp)
                .thenReturn(emptyResp)
                .thenReturn(emailResp);
        JSONObject payload = new JSONObject()
                .put("cardnumber", "201110640")
                .put("userid", "201110640")
                .put("email", "robertoestrada@upeu.edu.pe");
        JSONObject adopted = patronService.createPatron(payload);
        assertEquals(1944, adopted.getInt("patron_id"));
    }

    @Test
    void testCreatePatron_409_adoptByDni() throws Exception {
        // v1.3.10: el 409 es por el atributo extendido DNI (unique_id=1), NO por
        // cardnumber/userid/email. MidPoint envia cardnumber=codigo universitario y el DNI
        // real dentro de extended_attributes. El borrower legacy huerfano tiene cardnumber=DNI.
        // POST -> 409 ; GET cardnumber(codigo) -> [] ; GET userid(codigo) -> [] ; GET email -> [] ;
        // GET cardnumber(DNI) -> borrower legacy -> adopta por DNI.
        CloseableHttpResponse postResp = prepareResponse(409, "{\"error\":\"A patron record matching these details already exists\"}");
        CloseableHttpResponse emptyResp = prepareResponse(200, "[]");
        CloseableHttpResponse dniResp = prepareResponse(200, "[{\"patron_id\":4321,\"cardnumber\":\"45678901\",\"userid\":\"legacyuser\"}]");
        when(httpClient.execute(any(HttpPost.class))).thenReturn(postResp);
        // GET#1 cardnumber=codigo []; GET#2 userid=codigo []; GET#3 email []; GET#4 cardnumber=DNI match.
        when(httpClient.execute(any(HttpGet.class)))
                .thenReturn(emptyResp)
                .thenReturn(emptyResp)
                .thenReturn(emptyResp)
                .thenReturn(dniResp);
        JSONObject payload = new JSONObject()
                .put("cardnumber", "202010123")
                .put("userid", "202010123")
                .put("email", "student@upeu.edu.pe")
                .put("extended_attributes", new JSONArray()
                        .put(new JSONObject().put("type", "DNI").put("value", "45678901")));
        JSONObject adopted = patronService.createPatron(payload);
        assertEquals(4321, adopted.getInt("patron_id"));
    }

    @Test
    void testCreatePatron_409_adoptByDni_usesCardnumberDniExactLookup() throws Exception {
        // Verifica que el ultimo lookup de adopt por DNI consulta cardnumber=<DNI> con _match=exact.
        CloseableHttpResponse postResp = prepareResponse(409, "{\"error\":\"conflict\"}");
        CloseableHttpResponse emptyResp = prepareResponse(200, "[]");
        CloseableHttpResponse dniResp = prepareResponse(200, "[{\"patron_id\":99}]");
        when(httpClient.execute(any(HttpPost.class))).thenReturn(postResp);
        final java.util.List<HttpGet> gets = new java.util.ArrayList<>();
        when(httpClient.execute(any(HttpGet.class))).thenAnswer(inv -> {
            gets.add(inv.getArgument(0));
            // Solo el 4to GET (cardnumber=DNI) devuelve match.
            return gets.size() >= 4 ? dniResp : emptyResp;
        });
        JSONObject payload = new JSONObject()
                .put("cardnumber", "201912345")
                .put("userid", "201912345")
                .put("email", "x@upeu.edu.pe")
                .put("extended_attributes", new JSONArray()
                        .put(new JSONObject().put("type", "DNI").put("value", "70123456")));
        patronService.createPatron(payload);
        HttpGet dniGet = gets.get(gets.size() - 1);
        String uri = dniGet.getURI().toString();
        assertTrue(uri.contains("cardnumber=70123456"), "Debe buscar cardnumber=DNI. URI=" + uri);
        assertTrue(uri.contains("_match=exact"), "Debe forzar _match=exact. URI=" + uri);
    }

    @Test
    void testCreatePatron_409_noDni_throwsAlreadyExists() throws Exception {
        // Sin extended_attribute DNI y sin match por cardnumber/userid/email -> relanza 409.
        CloseableHttpResponse postResp = prepareResponse(409, "{\"error\":\"conflict\"}");
        CloseableHttpResponse emptyResp = prepareResponse(200, "[]");
        when(httpClient.execute(any(HttpPost.class))).thenReturn(postResp);
        when(httpClient.execute(any(HttpGet.class))).thenReturn(emptyResp);
        JSONObject payload = new JSONObject()
                .put("cardnumber", "202010999")
                .put("userid", "202010999")
                .put("email", "nodni@upeu.edu.pe");
        assertThrows(AlreadyExistsException.class, () -> patronService.createPatron(payload));
    }

    @Test
    void testCreatePatron_409_adoptUsesMatchExact() throws Exception {
        // Verifica que las busquedas de adopt fuerzan _match=exact (evita adoptar al
        // borrower equivocado por coincidencia parcial / fuzzy de Koha).
        CloseableHttpResponse postResp = prepareResponse(409, "{\"error\":\"A patron record matching these details already exists\"}");
        CloseableHttpResponse getResp = prepareResponse(200, "[{\"patron_id\":5,\"cardnumber\":\"C5\"}]");
        when(httpClient.execute(any(HttpPost.class))).thenReturn(postResp);
        final HttpGet[] captured = new HttpGet[1];
        when(httpClient.execute(any(HttpGet.class))).thenAnswer(inv -> {
            captured[0] = inv.getArgument(0);
            return getResp;
        });
        JSONObject payload = new JSONObject().put("cardnumber", "C5");
        patronService.createPatron(payload);
        assertNotNull(captured[0]);
        assertTrue(captured[0].getURI().toString().contains("_match=exact"),
                "La busqueda de adopt debe incluir _match=exact. URI=" + captured[0].getURI());
    }

    @Test
    void testCreatePatron_500_throwsConnectionFailedException() throws Exception {
        CloseableHttpResponse resp = prepareResponse(500, "{\"error\":\"Internal Server Error\"}");
        when(httpClient.execute(any(HttpPost.class))).thenReturn(resp);
        JSONObject payload = new JSONObject().put("userid", "newuser");
        assertThrows(ConnectionFailedException.class, () -> patronService.createPatron(payload));
    }
}
