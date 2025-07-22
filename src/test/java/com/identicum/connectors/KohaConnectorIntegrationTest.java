package com.identicum.connectors;

import com.identicum.connectors.services.CategoryService;
import com.identicum.connectors.services.PatronService;
import org.identityconnectors.framework.common.exceptions.ConnectorIOException;
import org.json.JSONArray;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

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
