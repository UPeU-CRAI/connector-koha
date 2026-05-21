package com.identicum.connectors.services;

import org.identityconnectors.framework.common.exceptions.ConnectionFailedException;
import org.identityconnectors.framework.common.exceptions.ConnectorIOException;
import org.identityconnectors.framework.common.exceptions.InvalidAttributeValueException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Pruebas de {@link PatronImageService} con el {@link JdbcConnectionProvider}
 * mockeado. Cubren la validacion de entrada y la traduccion de {@link SQLException}
 * a excepciones ConnId, sin requerir Docker ni una base de datos real.
 */
@ExtendWith(MockitoExtension.class)
class PatronImageServiceMockTest {

    @Mock
    private JdbcConnectionProvider provider;

    @Mock
    private Connection connection;

    @Mock
    private PreparedStatement statement;

    private PatronImageService service;

    @BeforeEach
    void setUp() {
        service = new PatronImageService(provider);
    }

    // --- Validacion de entrada (no llega a tocar la BD) ---

    @Test
    void upsertRejectsNonNumericBorrowernumber() {
        assertThrows(InvalidAttributeValueException.class,
                () -> service.upsertImage("ABC123", new byte[]{1, 2, 3}, "image/jpeg"));
        verifyNoInteractions(provider);
    }

    @Test
    void upsertRejectsNullBorrowernumber() {
        assertThrows(InvalidAttributeValueException.class,
                () -> service.upsertImage(null, new byte[]{1, 2, 3}, "image/jpeg"));
    }

    @Test
    void upsertRejectsEmptyImage() {
        assertThrows(InvalidAttributeValueException.class,
                () -> service.upsertImage("42", new byte[0], "image/jpeg"));
    }

    @Test
    void upsertRejectsNullImage() {
        assertThrows(InvalidAttributeValueException.class,
                () -> service.upsertImage("42", null, "image/jpeg"));
    }

    @Test
    void upsertRejectsOversizedImage() {
        byte[] tooBig = new byte[PatronImageService.MAX_IMAGE_BYTES + 1];
        assertThrows(InvalidAttributeValueException.class,
                () -> service.upsertImage("42", tooBig, "image/jpeg"));
    }

    @Test
    void upsertRejectsInvalidMimetype() {
        assertThrows(InvalidAttributeValueException.class,
                () -> service.upsertImage("42", new byte[]{1, 2, 3}, "image/gif"));
    }

    @Test
    void upsertRejectsNullMimetype() {
        assertThrows(InvalidAttributeValueException.class,
                () -> service.upsertImage("42", new byte[]{1, 2, 3}, null));
    }

    @Test
    void upsertAcceptsJpegAndPngMimetypes() throws SQLException {
        when(provider.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);

        assertDoesNotThrow(() -> service.upsertImage("42", new byte[]{1, 2, 3}, "image/jpeg"));
        assertDoesNotThrow(() -> service.upsertImage("42", new byte[]{1, 2, 3}, "image/png"));
        // Case-insensitive
        assertDoesNotThrow(() -> service.upsertImage("42", new byte[]{1, 2, 3}, "IMAGE/JPEG"));
    }

    // --- Traduccion de SQLException ---

    @Test
    void upsertTranslatesConnectionFailureToConnectionFailedException() throws SQLException {
        when(provider.getConnection())
                .thenThrow(new SQLNonTransientConnectionException("host unreachable"));
        assertThrows(ConnectionFailedException.class,
                () -> service.upsertImage("42", new byte[]{1, 2, 3}, "image/jpeg"));
    }

    @Test
    void upsertTranslatesGenericSqlExceptionToConnectorIOException() throws SQLException {
        when(provider.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate())
                .thenThrow(new SQLException("constraint violation", "23000"));
        assertThrows(ConnectorIOException.class,
                () -> service.upsertImage("42", new byte[]{1, 2, 3}, "image/jpeg"));
    }

    @Test
    void upsertTranslatesSqlState08ToConnectionFailedException() throws SQLException {
        when(provider.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        // SQLState clase "08" = connection exception
        when(statement.executeUpdate())
                .thenThrow(new SQLException("connection lost", "08S01"));
        assertThrows(ConnectionFailedException.class,
                () -> service.upsertImage("42", new byte[]{1, 2, 3}, "image/jpeg"));
    }

    @Test
    void deleteTranslatesGenericSqlExceptionToConnectorIOException() throws SQLException {
        when(provider.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate())
                .thenThrow(new SQLException("boom", "HY000"));
        assertThrows(ConnectorIOException.class, () -> service.deleteImage("42"));
    }

    @Test
    void deleteRejectsNonNumericBorrowernumber() {
        assertThrows(InvalidAttributeValueException.class,
                () -> service.deleteImage("not-a-number"));
    }

    @Test
    void deleteWithZeroRowsAffectedIsSuccess() throws SQLException {
        when(provider.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        // Fila inexistente: 0 filas afectadas, NO debe fallar.
        when(statement.executeUpdate()).thenReturn(0);
        assertDoesNotThrow(() -> service.deleteImage("999999"));
    }

    @Test
    void testConnectionDelegatesToProvider() {
        doNothing().when(provider).testConnection();
        assertDoesNotThrow(() -> service.testConnection());
        verify(provider, times(1)).testConnection();
    }
}
