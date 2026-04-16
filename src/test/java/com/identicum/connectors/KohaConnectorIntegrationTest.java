package com.identicum.connectors;

import com.identicum.connectors.services.CategoryService;
import com.identicum.connectors.services.PatronService;
import org.identityconnectors.framework.common.exceptions.ConnectorException;
import org.identityconnectors.framework.common.exceptions.ConnectorIOException;
import org.identityconnectors.framework.common.objects.Attribute;
import org.identityconnectors.framework.common.objects.AttributeBuilder;
import org.identityconnectors.framework.common.objects.ObjectClass;
import org.identityconnectors.framework.common.objects.OperationOptions;
import org.identityconnectors.framework.common.objects.OperationOptionsBuilder;
import org.identityconnectors.framework.common.objects.ResultsHandler;
import org.identityconnectors.framework.common.objects.Uid;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
public class KohaConnectorIntegrationTest {

    @Mock
    private PatronService patronService;
    @Mock
    private CategoryService categoryService;

    @Mock
    private KohaConfiguration configuration;

    @InjectMocks
    private KohaConnector connector;

    @BeforeEach
    void setup() {
        when(configuration.getServiceAddress()).thenReturn("http://localhost");
        when(configuration.getAuthenticationMethodStrategy()).thenReturn("BASIC");
        when(configuration.getUsername()).thenReturn("u");
    }

    @Test
    void testTestSuccessful() throws Exception {
        doNothing().when(patronService).testConnection();
        assertDoesNotThrow(() -> connector.test());
        verify(patronService, times(1)).testConnection();
    }

    @Test
    void testTestPropagatesException() throws Exception {
        doThrow(new ConnectorIOException("fail")).when(patronService).testConnection();
        assertThrows(ConnectorIOException.class, () -> connector.test());
    }

    @Test
    void executeQuery_byUid_returnsPatron() throws Exception {
        JSONObject patronJson = new JSONObject()
                .put("patron_id", 42)
                .put("userid", "jdoe")
                .put("surname", "Doe")
                .put("cardnumber", "12345")
                .put("library_id", "LIB1")
                .put("category_id", "S");
        when(patronService.getPatron("42")).thenReturn(patronJson);

        KohaFilter filter = new KohaFilter();
        filter.setByUid("42");

        List<String> foundUids = new ArrayList<>();
        ResultsHandler handler = connectorObject -> {
            foundUids.add(connectorObject.getUid().getUidValue());
            return true;
        };

        OperationOptions opts = new OperationOptionsBuilder().build();
        connector.executeQuery(ObjectClass.ACCOUNT, filter, handler, opts);

        assertEquals(1, foundUids.size());
        assertEquals("42", foundUids.get(0));
        verify(patronService, times(1)).getPatron("42");
    }

    @Test
    void executeQuery_byCardNumber_returnsPatron() throws Exception {
        JSONObject patronJson = new JSONObject()
                .put("patron_id", 7)
                .put("userid", "msmith")
                .put("surname", "Smith")
                .put("cardnumber", "ABC001")
                .put("library_id", "LIB1")
                .put("category_id", "S");

        doAnswer(invocation -> {
            java.util.function.Predicate<JSONObject> consumer = invocation.getArgument(2);
            consumer.test(patronJson);
            return null;
        }).when(patronService).searchPatrons(any(KohaFilter.class), any(), any());

        KohaFilter filter = new KohaFilter();
        filter.setByCardNumber("ABC001");

        List<String> foundUids = new ArrayList<>();
        ResultsHandler handler = connectorObject -> {
            foundUids.add(connectorObject.getUid().getUidValue());
            return true;
        };

        OperationOptions opts = new OperationOptionsBuilder().build();
        connector.executeQuery(ObjectClass.ACCOUNT, filter, handler, opts);

        assertEquals(1, foundUids.size());
        assertEquals("7", foundUids.get(0));
        verify(patronService, times(1)).searchPatrons(any(KohaFilter.class), any(), any());
    }

    @Test
    void create_patron_success() throws Exception {
        JSONObject createdPatron = new JSONObject().put("patron_id", 99);
        when(patronService.createPatron(any(JSONObject.class))).thenReturn(createdPatron);

        Set<Attribute> attrs = new HashSet<>();
        attrs.add(AttributeBuilder.build("userid", "newpatron"));
        attrs.add(AttributeBuilder.build("cardnumber", "CARD001"));
        attrs.add(AttributeBuilder.build("surname", "Patron"));
        attrs.add(AttributeBuilder.build("library_id", "LIB1"));
        attrs.add(AttributeBuilder.build("category_id", "S"));

        Uid uid = connector.create(ObjectClass.ACCOUNT, attrs, new OperationOptionsBuilder().build());

        assertNotNull(uid);
        assertEquals("99", uid.getUidValue());
        verify(patronService, times(1)).createPatron(any(JSONObject.class));
    }

    @Test
    void update_patron_success() throws Exception {
        JSONObject existingPatron = new JSONObject()
                .put("patron_id", 10)
                .put("userid", "olduser")
                .put("surname", "Old")
                .put("cardnumber", "CARD010")
                .put("library_id", "LIB1")
                .put("category_id", "S");
        when(patronService.getPatron("10")).thenReturn(existingPatron);
        doNothing().when(patronService).updatePatron(eq("10"), any(JSONObject.class));

        Set<Attribute> attrs = new HashSet<>();
        attrs.add(AttributeBuilder.build("surname", "New"));

        Uid result = connector.update(ObjectClass.ACCOUNT, new Uid("10"), attrs, new OperationOptionsBuilder().build());

        assertNotNull(result);
        assertEquals("10", result.getUidValue());
        verify(patronService, times(1)).updatePatron(eq("10"), any(JSONObject.class));
    }

    @Test
    void delete_patron_success() throws Exception {
        doNothing().when(patronService).deletePatron("5");

        assertDoesNotThrow(() -> connector.delete(ObjectClass.ACCOUNT, new Uid("5"), new OperationOptionsBuilder().build()));
        verify(patronService, times(1)).deletePatron("5");
    }

    @Test
    void create_group_throwsConnectorException() {
        Set<Attribute> attrs = new HashSet<>();
        attrs.add(AttributeBuilder.build("name", "ESTUDI"));

        assertThrows(UnsupportedOperationException.class,
            () -> connector.create(ObjectClass.GROUP, attrs, new OperationOptionsBuilder().build()));
    }

    @Test
    void update_group_throwsConnectorException() {
        Set<Attribute> attrs = new HashSet<>();
        attrs.add(AttributeBuilder.build("description", "Updated"));

        assertThrows(UnsupportedOperationException.class,
            () -> connector.update(ObjectClass.GROUP, new Uid("ESTUDI"), attrs, new OperationOptionsBuilder().build()));
    }

    @Test
    void delete_group_throwsConnectorException() {
        assertThrows(UnsupportedOperationException.class,
            () -> connector.delete(ObjectClass.GROUP, new Uid("ESTUDI"), new OperationOptionsBuilder().build()));
    }
}
