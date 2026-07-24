package com.identicum.connectors.services;

import org.identityconnectors.common.logging.Log;
import org.identityconnectors.framework.common.exceptions.ConfigurationException;
import org.identityconnectors.framework.common.exceptions.ConnectionFailedException;
import org.mariadb.jdbc.MariaDbPoolDataSource;

import javax.sql.DataSource;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

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
 *   <li><strong>{@code poolName} unico por instancia</strong> y credenciales dentro
 *       de la URL. Ver la nota de aislamiento mas abajo: no es cosmetico, evita un
 *       fallo permanente en produccion.</li>
 * </ul>
 *
 * <h2>Aislamiento del pool (regresion v1.4.0)</h2>
 *
 * <p>{@code MariaDbPoolDataSource} NO crea un pool propio: delega en el registro
 * <strong>estatico y global</strong> {@code org.mariadb.jdbc.pool.Pools}, indexado por la
 * {@code Configuration} resultante. Dos instancias del conector con identica configuracion
 * (host, BD, usuario, {@code maxPoolSize}) obtienen <strong>el mismo objeto {@code Pool}</strong>.</p>
 *
 * <p>Como MidPoint/ConnId instancia y descarta conectores mediante su propio connector pool,
 * el {@code dispose()} de UNA instancia invocaba {@code close()} y destruia el pool compartido,
 * dejando a las demas instancias vivas con una referencia muerta. A partir de ese momento cada
 * operacion JDBC fallaba de forma permanente con
 * {@code "No connection available within the specified time (option 'connectTimeout': 10,000 ms)"},
 * sin recuperarse ni siquiera reiniciando MidPoint (el bug se re-dispara al primer dispose()).</p>
 *
 * <p>Por eso cada provider genera un {@code poolName} unico: fuerza una {@code Configuration}
 * distinta, por lo tanto un {@code Pool} propio, y {@code close()} solo afecta al suyo.</p>
 *
 * <p>Ademas, cada uno de {@code setUrl()}, {@code setUser()} y {@code setPassword()} dispara la
 * creacion de un pool. Encadenarlos creaba dos pools huerfanos previos al bueno (uno con el
 * usuario del sistema operativo y otro con el usuario de BD, ambos <em>sin contrasena</em>), que
 * fallaban autenticacion y nunca se cerraban. Pasando las credenciales en la URL se crea
 * <strong>un unico</strong> pool, ya autenticado.</p>
 *
 * <p>Ciclo de vida: se crea en {@code KohaConnector.init()} y se cierra en
 * {@code KohaConnector.dispose()}.</p>
 */
public class JdbcConnectionProvider {

    private static final Log LOG = Log.getLog(JdbcConnectionProvider.class);

    /**
     * Discriminante de {@code poolName}. El contador cubre las instancias dentro de una
     * misma JVM; el sufijo aleatorio evita colisiones si el conector se recarga bajo otro
     * classloader (el estatico se reinicia, el registro de pools del driver no siempre).
     */
    private static final AtomicLong POOL_SEQ = new AtomicLong();
    private static final String POOL_NAME_PREFIX = "koha-connector-";

    private final MariaDbPoolDataSource dataSource;
    private final String poolName;

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
        // poolName unico: garantiza un Pool propio en el registro estatico del driver,
        // para que el dispose() de otra instancia del conector no destruya este pool.
        this.poolName = POOL_NAME_PREFIX + POOL_SEQ.incrementAndGet()
                + "-" + UUID.randomUUID().toString().substring(0, 8);
        // useUnicode + UTF-8 para consistencia del blob/mimetype.
        // maxPoolSize fija el tope del pool nativo del driver.
        // connectTimeout limita el bloqueo si la BD no responde.
        // Las credenciales van en la URL para que se cree UN SOLO pool, ya autenticado
        // (encadenar setUser/setPassword creaba pools huerfanos sin contrasena).
        // minPoolSize=1: el driver abre minPoolSize conexiones de forma EAGER en el
        // constructor del pool y, por defecto, minPoolSize == maxPoolSize. Como ConnId
        // crea un conector (y por tanto un pool) por operacion mientras KohaConnector no
        // sea PoolableConnector, el default abria 'poolSize' conexiones por operacion.
        // registerJmxPool=false: sin esto cada pool registra un MBean; con poolName unico
        // serian miles de MBeans distintos y un dispose() perdido los deja para siempre.
        String url = "jdbc:mariadb://" + host + ":" + port + "/" + database
                + "?useUnicode=true&characterEncoding=UTF-8"
                + "&maxPoolSize=" + poolSize
                + "&minPoolSize=1"
                + "&registerJmxPool=false"
                + "&connectTimeout=10000"
                + "&poolName=" + urlEncode(this.poolName)
                + "&user=" + urlEncode(user)
                + "&password=" + urlEncode(password);
        try {
            // Instanciacion directa del DataSource: no DriverManager, no ServiceLoader.
            // Solo setUrl(): cada setter adicional crearia otro pool.
            this.dataSource = new MariaDbPoolDataSource();
            this.dataSource.setUrl(url);
            LOG.ok("JDBC pool MariaDB inicializado hacia {0}:{1}/{2} (maxPoolSize={3}, poolName={4}).",
                    host, port, database, poolSize, this.poolName);
        } catch (SQLException e) {
            LOG.error(e, "No se pudo inicializar el pool JDBC de MariaDB.");
            throw new ConfigurationException(
                    "No se pudo inicializar el canal JDBC hacia la base de datos de Koha: " + e.getMessage(), e);
        }
    }

    /**
     * Codifica un valor para incrustarlo como parametro de la URL JDBC. Sin esto, una
     * contrasena con {@code &}, {@code =} o {@code #} rompe el parseo de la URL y produce
     * un fallo de autenticacion dificil de diagnosticar.
     */
    private static String urlEncode(String value) {
        if (value == null) {
            return "";
        }
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            // UTF-8 siempre esta presente en una JVM valida; inalcanzable en la practica.
            throw new ConfigurationException("La JVM no soporta UTF-8 para codificar la URL JDBC.", e);
        }
    }

    /**
     * Nombre del pool asignado a esta instancia. Util para diagnosticar en logs que cada
     * instancia del conector tiene su propio pool.
     *
     * @return el {@code poolName} unico de este provider
     */
    public String getPoolName() {
        return poolName;
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
     *
     * <p>Se puede invocar varias veces sin propagar error, pero conviene saber que esa
     * tolerancia la aporta el {@code try/catch} de aqui, NO el driver:
     * {@code MariaDbPoolDataSource.close()} delega en {@code pool.close()} sin comprobar
     * nulos, de modo que lanzaria NPE si la construccion del pool hubiera fallado.</p>
     *
     * <p>Nota de rendimiento: {@code Pool.close()} puede tardar hasta ~20 s si quedan
     * conexiones prestadas (espera la terminacion de sus executors). Es otra razon para
     * no crear y destruir un pool por operacion.</p>
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
