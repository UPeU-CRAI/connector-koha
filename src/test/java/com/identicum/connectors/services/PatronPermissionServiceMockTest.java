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
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    @Test
    void replaceAuthorizationWritesFlagsAndGranularPermissionsInOneTransaction() throws SQLException {
        PreparedStatement lockPatron = org.mockito.Mockito.mock(PreparedStatement.class);
        PreparedStatement updateFlags = org.mockito.Mockito.mock(PreparedStatement.class);
        PreparedStatement deletePermissions = org.mockito.Mockito.mock(PreparedStatement.class);
        PreparedStatement insertPermission = org.mockito.Mockito.mock(PreparedStatement.class);
        when(provider.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement("SELECT 1 FROM borrowers WHERE borrowernumber = ? FOR UPDATE"))
                .thenReturn(lockPatron);
        when(connection.prepareStatement("UPDATE borrowers SET flags = ? WHERE borrowernumber = ?"))
                .thenReturn(updateFlags);
        when(connection.prepareStatement("DELETE FROM user_permissions WHERE borrowernumber = ?"))
                .thenReturn(deletePermissions);
        when(connection.prepareStatement("INSERT INTO user_permissions (borrowernumber, module_bit, code) VALUES (?, ?, ?)"))
                .thenReturn(insertPermission);
        when(updateFlags.executeUpdate()).thenReturn(1);
        ResultSet patronExists = org.mockito.Mockito.mock(ResultSet.class);
        when(lockPatron.executeQuery()).thenReturn(patronExists);
        when(patronExists.next()).thenReturn(true);

        Set<String> permissions = new LinkedHashSet<>(Arrays.asList(
                "1:circulate_remaining_permissions",
                "4:edit_borrowers",
                "4:list_borrowers"));

        service.replaceAuthorization("16198", 4, permissions);

        verify(connection).setAutoCommit(false);
        verify(updateFlags).setInt(1, 4);
        verify(updateFlags).setInt(2, 16198);
        verify(deletePermissions).setInt(1, 16198);
        verify(insertPermission, org.mockito.Mockito.times(3)).setInt(1, 16198);
        verify(insertPermission, org.mockito.Mockito.times(3)).addBatch();
        verify(insertPermission).executeBatch();
        verify(connection).commit();
    }

    @Test
    void getPermissionsReturnsCanonicalModuleAndCodeValues() throws SQLException {
        when(provider.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, true, false);
        when(resultSet.getInt("module_bit")).thenReturn(1, 4);
        when(resultSet.getString("code"))
                .thenReturn("circulate_remaining_permissions", "edit_borrowers");

        Set<String> permissions = service.getPermissions("16198");

        assertEquals(new LinkedHashSet<>(Arrays.asList(
                "1:circulate_remaining_permissions", "4:edit_borrowers")), permissions);
    }

    @Test
    void replaceAuthorizationRejectsMalformedPermissionBeforeOpeningConnection() {
        Set<String> malformed = new LinkedHashSet<>(Arrays.asList("edit_borrowers"));

        InvalidAttributeValueException error = assertThrows(InvalidAttributeValueException.class,
                () -> service.replaceAuthorization("16198", 4, malformed));

        assertTrue(error.getMessage().contains("module_bit:code"));
        verifyNoInteractions(provider);
    }

    @Test
    void replaceAuthorizationStillReplacesPermissionsWhenFlagsAreUnchanged() throws SQLException {
        PreparedStatement lockPatron = org.mockito.Mockito.mock(PreparedStatement.class);
        PreparedStatement updateFlags = org.mockito.Mockito.mock(PreparedStatement.class);
        PreparedStatement deletePermissions = org.mockito.Mockito.mock(PreparedStatement.class);
        PreparedStatement insertPermission = org.mockito.Mockito.mock(PreparedStatement.class);
        ResultSet patronExists = org.mockito.Mockito.mock(ResultSet.class);
        when(provider.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement("SELECT 1 FROM borrowers WHERE borrowernumber = ? FOR UPDATE"))
                .thenReturn(lockPatron);
        when(connection.prepareStatement("UPDATE borrowers SET flags = ? WHERE borrowernumber = ?"))
                .thenReturn(updateFlags);
        when(connection.prepareStatement("DELETE FROM user_permissions WHERE borrowernumber = ?"))
                .thenReturn(deletePermissions);
        when(connection.prepareStatement("INSERT INTO user_permissions (borrowernumber, module_bit, code) VALUES (?, ?, ?)"))
                .thenReturn(insertPermission);
        when(lockPatron.executeQuery()).thenReturn(patronExists);
        when(patronExists.next()).thenReturn(true);
        when(updateFlags.executeUpdate()).thenReturn(0);

        service.replaceAuthorization("16198", 4,
                new LinkedHashSet<>(Arrays.asList("4:edit_borrowers")));

        verify(deletePermissions).executeUpdate();
        verify(connection).commit();
    }
}
