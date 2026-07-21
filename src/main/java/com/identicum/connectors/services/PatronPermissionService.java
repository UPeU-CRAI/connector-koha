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

/**
 * Canal JDBC gestionado para {@code borrowers.flags}.
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
