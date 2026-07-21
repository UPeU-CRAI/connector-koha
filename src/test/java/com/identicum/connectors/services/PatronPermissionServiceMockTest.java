package com.identicum.connectors.services;

import org.identityconnectors.framework.common.exceptions.InvalidAttributeValueException;
import org.identityconnectors.framework.common.exceptions.UnknownUidException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PatronPermissionServiceMockTest {

    @Mock private JdbcConnectionProvider provider;
    @Mock private Connection connection;
    @Mock private PreparedStatement statement;
    @Mock private ResultSet resultSet;

    private PatronPermissionService service;

    @BeforeEach
    void setUp() {
        service = new PatronPermissionService(provider);
    }

    @Test
    void getFlagsReturnsStoredBitmask() throws SQLException {
        when(provider.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getObject("flags", Integer.class)).thenReturn(1);

        assertEquals(1, service.getFlags("16198"));
        verify(statement).setInt(1, 16198);
    }

    @Test
    void getFlagsPreservesSqlNull() throws SQLException {
        when(provider.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getObject("flags", Integer.class)).thenReturn(null);

        assertNull(service.getFlags("16198"));
    }

    @Test
    void updateFlagsWritesSuperlibrarianBitmask() throws SQLException {
        when(provider.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);

        service.updateFlags("16198", 1);

        verify(statement).setInt(1, 1);
        verify(statement).setInt(2, 16198);
    }

    @Test
    void updateFlagsCanClearToSqlNullForRollback() throws SQLException {
        when(provider.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);

        service.updateFlags("16198", null);

        verify(statement).setNull(1, Types.INTEGER);
        verify(statement).setInt(2, 16198);
    }

    @Test
    void updateFlagsRejectsNegativeBitmask() {
        assertThrows(InvalidAttributeValueException.class,
                () -> service.updateFlags("16198", -1));
        verifyNoInteractions(provider);
    }

    @Test
    void missingPatronIsNotReportedAsSuccessfulUpdate() throws SQLException {
        when(provider.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(0);

        assertThrows(UnknownUidException.class,
                () -> service.updateFlags("999999", 1));
    }
}
