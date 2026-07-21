package com.identicum.connectors.services;

import org.identityconnectors.common.logging.Log;
import org.identityconnectors.framework.common.exceptions.ConnectionFailedException;
import org.identityconnectors.framework.common.exceptions.ConnectorIOException;
import org.identityconnectors.framework.common.exceptions.InvalidAttributeValueException;
import org.identityconnectors.framework.common.exceptions.UnknownUidException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;
import java.sql.Types;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Canal JDBC gestionado para {@code borrowers.flags} y
 * {@code user_permissions}.
 *
 * <p>Koha no publica este bitmask en su API REST de patrones. El conector usa
 * statements preparados, limitados a una fila identificada por
 * {@code borrowernumber}; no ejecuta SQL ad-hoc ni búsquedas por email/userid.</p>
 */
public class PatronPermissionService {

    private static final Log LOG = Log.getLog(PatronPermissionService.class);
    private static final String SQL_SELECT =
            "SELECT flags FROM borrowers WHERE borrowernumber = ?";
    private static final String SQL_UPDATE =
            "UPDATE borrowers SET flags = ? WHERE borrowernumber = ?";
    private static final String SQL_LOCK_PATRON =
            "SELECT 1 FROM borrowers WHERE borrowernumber = ? FOR UPDATE";
    private static final String SQL_SELECT_PERMISSIONS =
            "SELECT module_bit, code FROM user_permissions WHERE borrowernumber = ? ORDER BY module_bit, code";
    private static final String SQL_DELETE_PERMISSIONS =
            "DELETE FROM user_permissions WHERE borrowernumber = ?";
    private static final String SQL_INSERT_PERMISSION =
            "INSERT INTO user_permissions (borrowernumber, module_bit, code) VALUES (?, ?, ?)";
    private static final Pattern PERMISSION_PATTERN =
            Pattern.compile("^([0-9]{1,2}):([A-Za-z0-9_]{1,64})$");

    private final JdbcConnectionProvider connectionProvider;

    public PatronPermissionService(JdbcConnectionProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
    }

    public Integer getFlags(String borrowernumber) {
        int id = parseBorrowernumber(borrowernumber);
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_SELECT)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new UnknownUidException("No existe el patron Koha " + id + ".");
                }
                return rs.getObject("flags", Integer.class);
            }
        } catch (SQLException e) {
            throw translate(e, "leer flags del patron " + id);
        }
    }

    public void updateFlags(String borrowernumber, Integer flags) {
        int id = parseBorrowernumber(borrowernumber);
        if (flags != null && flags < 0) {
            throw new InvalidAttributeValueException(
                    "borrowers.flags debe ser un bitmask entero no negativo. Valor: " + flags);
        }
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_UPDATE)) {
            if (flags == null) {
                ps.setNull(1, Types.INTEGER);
            } else {
                ps.setInt(1, flags);
            }
            ps.setInt(2, id);
            int affected = ps.executeUpdate();
            if (affected != 1) {
                throw new UnknownUidException(
                        "No se actualizo borrowers.flags: no existe el patron Koha " + id + ".");
            }
            LOG.ok("updateFlags: patron {0}, flags={1}, filas afectadas={2}.", id, flags, affected);
        } catch (SQLException e) {
            throw translate(e, "actualizar flags del patron " + id);
        }
    }

    public Set<String> getPermissions(String borrowernumber) {
        int id = parseBorrowernumber(borrowernumber);
        Set<String> permissions = new LinkedHashSet<>();
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_SELECT_PERMISSIONS)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    permissions.add(rs.getInt("module_bit") + ":" + rs.getString("code"));
                }
            }
            return permissions;
        } catch (SQLException e) {
            throw translate(e, "leer permisos granulares del patron " + id);
        }
    }

    /**
     * Reemplaza el bitmask y el conjunto completo de permisos granulares en una
     * unica transaccion. Cada permiso usa el formato canonico
     * {@code module_bit:code}, por ejemplo {@code 4:edit_borrowers}.
     */
    public void replaceAuthorization(String borrowernumber, Integer flags, Set<String> permissions) {
        int id = parseBorrowernumber(borrowernumber);
        if (flags != null && flags < 0) {
            throw new InvalidAttributeValueException(
                    "borrowers.flags debe ser un bitmask entero no negativo. Valor: " + flags);
        }
        Set<PermissionKey> parsed = parsePermissions(permissions);
        Connection conn = null;
        boolean previousAutoCommit = true;
        try {
            conn = connectionProvider.getConnection();
            previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement lockPatron = conn.prepareStatement(SQL_LOCK_PATRON);
                 PreparedStatement updateFlags = conn.prepareStatement(SQL_UPDATE);
                 PreparedStatement deletePermissions = conn.prepareStatement(SQL_DELETE_PERMISSIONS);
                 PreparedStatement insertPermission = conn.prepareStatement(SQL_INSERT_PERMISSION)) {
                lockPatron.setInt(1, id);
                try (ResultSet rs = lockPatron.executeQuery()) {
                    if (!rs.next()) {
                        throw new UnknownUidException("No existe el patron Koha " + id + ".");
                    }
                }
                if (flags == null) {
                    updateFlags.setNull(1, Types.INTEGER);
                } else {
                    updateFlags.setInt(1, flags);
                }
                updateFlags.setInt(2, id);
                updateFlags.executeUpdate();

                deletePermissions.setInt(1, id);
                deletePermissions.executeUpdate();
                for (PermissionKey permission : parsed) {
                    insertPermission.setInt(1, id);
                    insertPermission.setInt(2, permission.moduleBit);
                    insertPermission.setString(3, permission.code);
                    insertPermission.addBatch();
                }
                if (!parsed.isEmpty()) {
                    insertPermission.executeBatch();
                }
                conn.commit();
                LOG.ok("replaceAuthorization: patron {0}, flags={1}, permisos={2}.",
                        id, flags, parsed.size());
            }
        } catch (SQLException e) {
            rollbackQuietly(conn, id);
            throw translate(e, "reemplazar autorizacion del patron " + id);
        } catch (RuntimeException e) {
            rollbackQuietly(conn, id);
            throw e;
        } finally {
            if (conn != null) {
                try {
                    conn.setAutoCommit(previousAutoCommit);
                    conn.close();
                } catch (SQLException e) {
                    LOG.warn(e, "No se pudo restaurar/cerrar la conexion de autorizacion del patron {0}.", id);
                }
            }
        }
    }

    private Set<PermissionKey> parsePermissions(Set<String> permissions) {
        Set<PermissionKey> parsed = new LinkedHashSet<>();
        if (permissions == null) {
            return parsed;
        }
        for (String raw : permissions) {
            Matcher matcher = raw == null ? null : PERMISSION_PATTERN.matcher(raw.trim());
            if (matcher == null || !matcher.matches()) {
                throw new InvalidAttributeValueException(
                        "Permiso Koha invalido; use module_bit:code. Valor: " + raw);
            }
            parsed.add(new PermissionKey(Integer.parseInt(matcher.group(1)), matcher.group(2)));
        }
        return parsed;
    }

    private void rollbackQuietly(Connection conn, int id) {
        if (conn == null) {
            return;
        }
        try {
            conn.rollback();
        } catch (SQLException rollbackError) {
            LOG.error(rollbackError, "Fallo rollback de autorizacion del patron {0}.", id);
        }
    }

    private static final class PermissionKey {
        private final int moduleBit;
        private final String code;

        private PermissionKey(int moduleBit, String code) {
            this.moduleBit = moduleBit;
            this.code = code;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof PermissionKey)) return false;
            PermissionKey that = (PermissionKey) other;
            return moduleBit == that.moduleBit && code.equals(that.code);
        }

        @Override
        public int hashCode() {
            return 31 * moduleBit + code.hashCode();
        }
    }

    public void testConnection() {
        connectionProvider.testConnection();
    }

    private int parseBorrowernumber(String borrowernumber) {
        if (borrowernumber == null || borrowernumber.trim().isEmpty()) {
            throw new InvalidAttributeValueException("El borrowernumber no puede ser nulo o vacio.");
        }
        try {
            return Integer.parseInt(borrowernumber.trim());
        } catch (NumberFormatException e) {
            throw new InvalidAttributeValueException(
                    "El borrowernumber debe ser numerico. Valor: '" + borrowernumber + "'.", e);
        }
    }

    private RuntimeException translate(SQLException e, String action) {
        LOG.error(e, "Error JDBC al {0}. SQLState={1}, errorCode={2}.",
                action, e.getSQLState(), e.getErrorCode());
        if (e instanceof SQLTransientConnectionException
                || e instanceof SQLNonTransientConnectionException
                || (e.getSQLState() != null && e.getSQLState().startsWith("08"))) {
            return new ConnectionFailedException(
                    "Fallo la conexion JDBC al " + action + ": " + e.getMessage(), e);
        }
        return new ConnectorIOException(
                "Error JDBC al " + action + ": " + e.getMessage(), e);
    }
}
