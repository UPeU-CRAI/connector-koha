package com.identicum.connectors.services;

import org.identityconnectors.common.logging.Log;
import org.identityconnectors.framework.common.exceptions.ConnectionFailedException;
import org.identityconnectors.framework.common.exceptions.ConnectorIOException;
import org.identityconnectors.framework.common.exceptions.InvalidAttributeValueException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Servicio JDBC dedicado a provisionar la foto del patron en la tabla
 * {@code patronimage} de Koha.
 *
 * <p>La API REST de Koha 25.11 no expone un endpoint para la imagen del patron,
 * por eso este conector es hibrido: REST para los ~48 atributos del patron,
 * y este canal JDBC <strong>exclusivamente</strong> para la foto.</p>
 *
 * <p>Esquema real de la tabla (capturado de Koha PROD {@code koha_upeu}):</p>
 * <pre>
 * CREATE TABLE patronimage (
 *   borrowernumber int(11) NOT NULL,
 *   mimetype       varchar(15) NOT NULL,
 *   imagefile      mediumblob NOT NULL,
 *   PRIMARY KEY (borrowernumber),
 *   CONSTRAINT patronimage_fk1 FOREIGN KEY (borrowernumber)
 *       REFERENCES borrowers (borrowernumber) ON DELETE CASCADE ON UPDATE CASCADE
 * ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
 * </pre>
 *
 * <p>La clave es siempre {@code borrowernumber} (= {@code patron_id} = {@code __UID__}
 * en ConnId). NUNCA {@code cardnumber} ni {@code userid}. La tabla real NO tiene
 * columna {@code cardnumber}.</p>
 */
public class PatronImageService {

    private static final Log LOG = Log.getLog(PatronImageService.class);

    /**
     * Tope de tamano del blob. {@code mediumblob} admite hasta 16 MB, pero una
     * foto de carnet razonable no deberia superar los 5 MB; rechazamos algo mas
     * grande para no inyectar basura ni agotar memoria.
     */
    static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;

    /** Mimetypes permitidos para la foto del patron. */
    private static final Set<String> ALLOWED_MIMETYPES =
            new HashSet<>(Arrays.asList("image/jpeg", "image/png"));

    private static final String SQL_SELECT =
            "SELECT imagefile FROM patronimage WHERE borrowernumber = ?";

    // Upsert idempotente en un unico statement, sin transaccion explicita.
    private static final String SQL_UPSERT =
            "INSERT INTO patronimage (borrowernumber, mimetype, imagefile) VALUES (?, ?, ?) "
            + "ON DUPLICATE KEY UPDATE mimetype = VALUES(mimetype), imagefile = VALUES(imagefile)";

    private static final String SQL_DELETE =
            "DELETE FROM patronimage WHERE borrowernumber = ?";

    private final JdbcConnectionProvider connectionProvider;

    public PatronImageService(JdbcConnectionProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
    }

    /**
     * Obtiene la foto de un patron.
     *
     * @param borrowernumber clave del patron (= patron_id = __UID__)
     * @return los bytes de la imagen, o {@code null} si el patron no tiene foto
     * @throws InvalidAttributeValueException si el borrowernumber no es numerico
     * @throws ConnectionFailedException      si falla la conexion a la BD
     * @throws ConnectorIOException           para cualquier otro error JDBC
     */
    public byte[] getImage(String borrowernumber) {
        int id = parseBorrowernumber(borrowernumber);
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_SELECT)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    byte[] data = rs.getBytes("imagefile");
                    LOG.ok("getImage: patron {0} tiene foto ({1} bytes).",
                            id, data != null ? data.length : 0);
                    return data;
                }
                LOG.ok("getImage: patron {0} no tiene foto.", id);
                return null;
            }
        } catch (SQLException e) {
            throw translate(e, "leer la foto del patron " + id);
        }
    }

    /**
     * Inserta o actualiza la foto de un patron de forma idempotente.
     *
     * <p>Usa {@code INSERT ... ON DUPLICATE KEY UPDATE} en un unico statement,
     * por lo que no requiere transaccion explicita.</p>
     *
     * @param borrowernumber clave del patron (= patron_id = __UID__)
     * @param data           bytes de la imagen
     * @param mimetype       mimetype de la imagen (image/jpeg o image/png)
     * @throws InvalidAttributeValueException si los datos no son validos
     *                                        (vacios, demasiado grandes, mimetype no permitido)
     * @throws ConnectionFailedException      si falla la conexion a la BD
     * @throws ConnectorIOException           para cualquier otro error JDBC
     */
    public void upsertImage(String borrowernumber, byte[] data, String mimetype) {
        int id = parseBorrowernumber(borrowernumber);
        validateImage(data, mimetype);
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_UPSERT)) {
            ps.setInt(1, id);
            ps.setString(2, mimetype);
            ps.setBytes(3, data);
            int affected = ps.executeUpdate();
            LOG.ok("upsertImage: patron {0}, {1} bytes, mimetype={2}, filas afectadas={3}.",
                    id, data.length, mimetype, affected);
        } catch (SQLException e) {
            throw translate(e, "escribir la foto del patron " + id);
        }
    }

    /**
     * Elimina la foto de un patron.
     *
     * <p>Si el patron no tiene foto, 0 filas afectadas se considera exito
     * (operacion idempotente, no es un error).</p>
     *
     * @param borrowernumber clave del patron (= patron_id = __UID__)
     * @throws InvalidAttributeValueException si el borrowernumber no es numerico
     * @throws ConnectionFailedException      si falla la conexion a la BD
     * @throws ConnectorIOException           para cualquier otro error JDBC
     */
    public void deleteImage(String borrowernumber) {
        int id = parseBorrowernumber(borrowernumber);
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_DELETE)) {
            ps.setInt(1, id);
            int affected = ps.executeUpdate();
            LOG.ok("deleteImage: patron {0}, filas afectadas={1} (0 = sin foto previa, es exito).",
                    id, affected);
        } catch (SQLException e) {
            throw translate(e, "eliminar la foto del patron " + id);
        }
    }

    /**
     * Verifica que el canal JDBC esta operativo delegando en el provider.
     *
     * @throws ConnectionFailedException si la conexion falla
     */
    public void testConnection() {
        connectionProvider.testConnection();
    }

    // --- Helpers internos ---

    private int parseBorrowernumber(String borrowernumber) {
        if (borrowernumber == null || borrowernumber.trim().isEmpty()) {
            throw new InvalidAttributeValueException(
                    "El borrowernumber del patron no puede ser nulo o vacio para operar sobre patronimage.");
        }
        try {
            return Integer.parseInt(borrowernumber.trim());
        } catch (NumberFormatException e) {
            throw new InvalidAttributeValueException(
                    "El borrowernumber del patron debe ser numerico (patron_id de Koha). Valor recibido: '"
                    + borrowernumber + "'.", e);
        }
    }

    private void validateImage(byte[] data, String mimetype) {
        if (data == null || data.length == 0) {
            throw new InvalidAttributeValueException(
                    "La foto del patron no puede estar vacia.");
        }
        if (data.length > MAX_IMAGE_BYTES) {
            throw new InvalidAttributeValueException(
                    "La foto del patron excede el tamano maximo permitido ("
                    + MAX_IMAGE_BYTES + " bytes). Tamano recibido: " + data.length + " bytes.");
        }
        if (mimetype == null || !ALLOWED_MIMETYPES.contains(mimetype.toLowerCase())) {
            throw new InvalidAttributeValueException(
                    "El mimetype de la foto del patron no es valido. Permitidos: "
                    + ALLOWED_MIMETYPES + ". Valor recibido: '" + mimetype + "'.");
        }
    }

    /**
     * Traduce una {@link SQLException} a la excepcion ConnId apropiada,
     * de forma analoga a como {@code AbstractKohaService} traduce los errores HTTP.
     * Nunca deja escapar stacktraces crudos hacia MidPoint.
     */
    private RuntimeException translate(SQLException e, String accion) {
        LOG.error(e, "Error JDBC al {0}. SQLState={1}, errorCode={2}.",
                accion, e.getSQLState(), e.getErrorCode());
        if (isConnectionFailure(e)) {
            return new ConnectionFailedException(
                    "Fallo la conexion JDBC al " + accion + ": " + e.getMessage(), e);
        }
        return new ConnectorIOException(
                "Error JDBC al " + accion + ": " + e.getMessage(), e);
    }

    private boolean isConnectionFailure(SQLException e) {
        if (e instanceof SQLTransientConnectionException
                || e instanceof SQLNonTransientConnectionException) {
            return true;
        }
        // SQLState clase "08" = connection exception (estandar SQL).
        String state = e.getSQLState();
        return state != null && state.startsWith("08");
    }
}
