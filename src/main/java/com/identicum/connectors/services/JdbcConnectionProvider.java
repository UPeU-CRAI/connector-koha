package com.identicum.connectors.services;

import org.identityconnectors.common.logging.Log;
import org.identityconnectors.framework.common.exceptions.ConfigurationException;
import org.identityconnectors.framework.common.exceptions.ConnectionFailedException;
import org.mariadb.jdbc.MariaDbPoolDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Gestiona un pool de conexiones JDBC nativo de MariaDB ({@link MariaDbPoolDataSource})
 * hacia la base de datos de Koha.
 *
 * <p>Diseno deliberado:</p>
 * <ul>
 *   <li>Se instancia el {@code DataSource} directamente, sin {@code DriverManager}
 *       y sin depender del SPI {@code ServiceLoader} de JDBC: el classloader
 *       aislado de ConnId rompe el descubrimiento de drivers via {@code ServiceLoader}.</li>
 *   <li>NO se usa HikariCP: su descubrimiento de driver tambien depende del
 *       {@code ServiceLoader} y entra en conflicto con el classloader del conector.
 *       El pool nativo del driver de MariaDB no tiene ese problema.</li>
 *   <li>La URL JDBC fuerza {@code useUnicode=true&characterEncoding=UTF-8} para
 *       que el {@code mediumblob} y el {@code mimetype} se manejen de forma consistente.</li>
 * </ul>
 *
 * <p>Ciclo de vida: se crea en {@code KohaConnector.init()} y se cierra en
 * {@code KohaConnector.dispose()}.</p>
 */
public class JdbcConnectionProvider {

    private static final Log LOG = Log.getLog(JdbcConnectionProvider.class);

    private final MariaDbPoolDataSource dataSource;

    /**
     * Crea el provider y abre el pool de conexiones.
     *
     * @param host     host del servidor MariaDB
     * @param port     puerto del servidor MariaDB
     * @param database nombre del esquema de Koha
     * @param user     usuario MariaDB
     * @param password contrasena MariaDB (en claro, ya extraida del GuardedString)
     * @param poolSize tamano maximo del pool
     * @throws ConfigurationException si el DataSource no se puede inicializar
     */
    public JdbcConnectionProvider(String host, int port, String database,
                                  String user, String password, int poolSize) {
        // useUnicode + UTF-8 para consistencia del blob/mimetype.
        // maxPoolSize fija el tope del pool nativo del driver.
        // connectTimeout limita el bloqueo si la BD no responde.
        String url = "jdbc:mariadb://" + host + ":" + port + "/" + database
                + "?useUnicode=true&characterEncoding=UTF-8"
                + "&maxPoolSize=" + poolSize
                + "&connectTimeout=10000";
        try {
            // Instanciacion directa del DataSource: no DriverManager, no ServiceLoader.
            this.dataSource = new MariaDbPoolDataSource();
            this.dataSource.setUrl(url);
            this.dataSource.setUser(user);
            this.dataSource.setPassword(password);
            LOG.ok("JDBC pool MariaDB inicializado hacia {0}:{1}/{2} (maxPoolSize={3}).",
                    host, port, database, poolSize);
        } catch (SQLException e) {
            LOG.error(e, "No se pudo inicializar el pool JDBC de MariaDB.");
            throw new ConfigurationException(
                    "No se pudo inicializar el canal JDBC hacia la base de datos de Koha: " + e.getMessage(), e);
        }
    }

    /**
     * Obtiene una conexion del pool. El llamador es responsable de cerrarla
     * (preferiblemente con try-with-resources), lo que la devuelve al pool.
     *
     * @return conexion JDBC del pool
     * @throws SQLException si no se puede obtener una conexion
     */
    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    /**
     * Verifica que el canal JDBC esta operativo obteniendo y validando una conexion.
     *
     * @throws ConnectionFailedException si la conexion falla
     */
    public void testConnection() {
        try (Connection conn = dataSource.getConnection()) {
            if (conn == null || !conn.isValid(5)) {
                throw new ConnectionFailedException(
                        "El canal JDBC hacia la base de datos de Koha no esta operativo (conexion invalida).");
            }
            LOG.ok("Test del canal JDBC: conexion valida.");
        } catch (SQLException e) {
            LOG.error(e, "Fallo el test del canal JDBC hacia la base de datos de Koha.");
            throw new ConnectionFailedException(
                    "Fallo la conexion JDBC hacia la base de datos de Koha: " + e.getMessage(), e);
        }
    }

    /**
     * Expone el {@link DataSource} subyacente. Util para pruebas.
     *
     * @return el DataSource del pool
     */
    public DataSource getDataSource() {
        return dataSource;
    }

    /**
     * Cierra el pool de conexiones y libera todos los recursos.
     * Idempotente: invocarlo varias veces no causa error.
     */
    public void close() {
        try {
            dataSource.close();
            LOG.ok("JDBC pool MariaDB cerrado.");
        } catch (Exception e) {
            LOG.warn(e, "Error al cerrar el pool JDBC de MariaDB: {0}", e.getMessage());
        }
    }
}
