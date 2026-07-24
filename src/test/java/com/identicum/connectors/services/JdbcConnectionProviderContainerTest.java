package com.identicum.connectors.services;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MariaDBContainer;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pruebas de integracion de {@link JdbcConnectionProvider} contra una MariaDB real.
 *
 * <p>El canal JDBC del conector existe unicamente para {@code borrowers.flags} y
 * {@code user_permissions} (permisos granulares que Koha no expone por REST). Estas pruebas
 * verifican el ciclo de vida del pool, no operaciones de negocio, por lo que no necesitan
 * crear ninguna tabla.</p>
 *
 * <p>Dos modos de ejecucion, en orden de preferencia:</p>
 * <ol>
 *   <li><strong>BD externa</strong>: si se pasan las system properties
 *       {@code koha.test.db.host}, {@code koha.test.db.port}, {@code koha.test.db.name},
 *       {@code koha.test.db.user}, {@code koha.test.db.pass}, se usa esa MariaDB ya
 *       levantada. Util cuando Testcontainers no es viable en el runner (por ejemplo,
 *       compilando dentro de un contenedor sin acceso al socket de Docker).</li>
 *   <li><strong>Testcontainers</strong>: si no hay BD externa pero Docker esta disponible,
 *       se levanta MariaDB automaticamente.</li>
 * </ol>
 *
 * <p>Si ninguno de los dos esta disponible, toda la clase se salta.</p>
 */
class JdbcConnectionProviderContainerTest {

    private static MariaDBContainer<?> mariaDb;
    private static boolean enabled = false;

    private static String dbHost;
    private static int dbPort;
    private static String dbName;
    private static String dbUser;
    private static String dbPass;

    private static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            System.out.println("[JdbcConnectionProviderContainerTest] Docker no disponible: "
                    + t.getClass().getName() + ": " + t.getMessage());
            return false;
        }
    }

    private static boolean hasExternalDb() {
        return System.getProperty("koha.test.db.host") != null;
    }

    @BeforeAll
    static void startContainer() {
        if (hasExternalDb()) {
            dbHost = System.getProperty("koha.test.db.host");
            dbPort = Integer.parseInt(System.getProperty("koha.test.db.port", "3306"));
            dbName = System.getProperty("koha.test.db.name", "koha_test");
            dbUser = System.getProperty("koha.test.db.user", "koha");
            dbPass = System.getProperty("koha.test.db.pass", "kohapass");
            System.out.println("[JdbcConnectionProviderContainerTest] Usando BD externa "
                    + dbHost + ":" + dbPort + "/" + dbName);
        } else if (dockerAvailable()) {
            mariaDb = new MariaDBContainer<>("mariadb:10.11")
                    .withDatabaseName("koha_test")
                    .withUsername("koha")
                    .withPassword("kohapass");
            mariaDb.start();
            dbHost = mariaDb.getHost();
            dbPort = mariaDb.getFirstMappedPort();
            dbName = mariaDb.getDatabaseName();
            dbUser = mariaDb.getUsername();
            dbPass = mariaDb.getPassword();
            System.out.println("[JdbcConnectionProviderContainerTest] Usando Testcontainers MariaDB");
        } else {
            assumeTrue(false,
                    "Ni BD externa ni Docker disponibles: se omiten las pruebas de integracion JDBC.");
            return;
        }
        enabled = true;
    }

    @AfterAll
    static void stopContainer() {
        if (mariaDb != null) {
            mariaDb.stop();
        }
    }

    @Test
    void testConnectionSucceedsAgainstRealDatabase() {
        assumeTrue(enabled, "Sin BD disponible");
        JdbcConnectionProvider provider =
                new JdbcConnectionProvider(dbHost, dbPort, dbName, dbUser, dbPass, 2);
        try {
            assertDoesNotThrow(provider::testConnection);
        } finally {
            provider.close();
        }
    }

    /**
     * Regresion del bug de v1.4.0, contra una MariaDB real.
     *
     * <p>{@code MariaDbPoolDataSource} delega en el registro estatico
     * {@code org.mariadb.jdbc.pool.Pools}, indexado por {@code Configuration}: dos providers
     * con identica configuracion compartian el mismo {@code Pool}. Cuando ConnId invocaba
     * {@code dispose()} sobre UNA instancia del conector, su {@code close()} destruia el pool
     * de las demas, que quedaban fallando de forma permanente con
     * "No connection available within the specified time".</p>
     *
     * <p>Necesita BD real: sin ella, un host inalcanzable produce ese mismo mensaje por otra
     * causa y la prueba no distinguiria nada.</p>
     */
    @Test
    void closingOneProviderDoesNotBreakAnother() {
        assumeTrue(enabled, "Sin BD disponible");
        JdbcConnectionProvider a = new JdbcConnectionProvider(dbHost, dbPort, dbName, dbUser, dbPass, 2);
        JdbcConnectionProvider b = new JdbcConnectionProvider(dbHost, dbPort, dbName, dbUser, dbPass, 2);
        try {
            assertNotEquals(a.getPoolName(), b.getPoolName(),
                    "Cada provider debe tener su propio poolName.");
            a.testConnection();
            b.testConnection();

            a.close(); // simula el dispose() de una instancia del conector

            // B debe seguir plenamente operativo.
            assertDoesNotThrow(b::testConnection,
                    "El close() de A destruyo el pool compartido de B (regresion v1.4.0).");
        } finally {
            b.close();
        }
    }
}
