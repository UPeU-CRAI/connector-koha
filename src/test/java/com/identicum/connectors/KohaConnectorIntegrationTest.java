package com.identicum.connectors;

import com.identicum.connectors.services.CategoryService;
import com.identicum.connectors.services.PatronService;
import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.common.exceptions.ConnectorIOException;
import org.json.JSONArray;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class KohaConnectorIntegrationTest {

    @Mock
    private PatronService patronService;
    @Mock
    private CategoryService categoryService;

    private KohaConnector connector;

    @BeforeEach
    void setup() throws Exception {
        connector = new KohaConnector();
        KohaConfiguration cfg = new KohaConfiguration();
        cfg.setServiceAddress("http://localhost");
        cfg.setAuthenticationMethodStrategy("BASIC");
        cfg.setUsername("u");
        cfg.setPassword(new GuardedString("p".toCharArray()));
        connector.init(cfg);
        injectField(connector, "patronService", patronService);
        injectField(connector, "categoryService", categoryService);
    }

    private static void injectField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void testTestSuccessful() throws Exception {
        when(patronService.searchPatrons(any(), any())).thenReturn(new JSONArray());
        assertDoesNotThrow(() -> connector.test());
        verify(patronService, times(1)).searchPatrons(any(), any());
    }

    @Test
    void testTestPropagatesException() throws Exception {
        when(patronService.searchPatrons(any(), any())).thenThrow(new ConnectorIOException("fail"));
        assertThrows(ConnectorIOException.class, () -> connector.test());
    }
}
