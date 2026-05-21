package com.identicum.connectors.services;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MariaDBContainer;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pruebas de integracion reales de {@link PatronImageService} contra una
 * instancia de MariaDB.
 *
 * <p>Se usa MariaDB y NO H2 deliberadamente: H2 no soporta la sintaxis
 * {@code INSERT ... ON DUPLICATE KEY UPDATE}, lo que daria falsos positivos
 * sobre la idempotencia del upsert.</p>
 *
 * <p>Dos modos de ejecucion, en orden de preferencia:</p>
 * <ol>
 *   <li><strong>BD externa</strong>: si se pasan las system properties
 *       {@code koha.test.db.host}, {@code koha.test.db.port},
 *       {@code koha.test.db.name}, {@code koha.test.db.user},
 *       {@code koha.test.db.pass}, se usa esa MariaDB ya levantada.
 *       Util cuando Testcontainers no es viable en el runner.</li>
 *   <li><strong>Testcontainers</strong>: si no hay BD externa pero Docker
 *       esta disponible, se levanta MariaDB automaticamente.</li>
 * </ol>
 *
 * <p>Si ninguno de los dos esta disponible, toda la clase se salta.</p>
 */
class PatronImageServiceContainerTest {

    private static MariaDBContainer<?> mariaDb;
    private static JdbcConnectionProvider provider;
    private static PatronImageService service;
    private static boolean enabled = false;

    private static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            System.out.println("[PatronImageServiceContainerTest] Docker no disponible: "
                    + t.getClass().getName() + ": " + t.getMessage());
            return false;
        }
    }

    private static boolean hasExternalDb() {
        return System.getProperty("koha.test.db.host") != null;
    }

    @BeforeAll
    static void startContainer() {
        String host;
        int port;
        String name;
        String user;
        String pass;

        if (hasExternalDb()) {
            host = System.getProperty("koha.test.db.host");
            port = Integer.parseInt(System.getProperty("koha.test.db.port", "3306"));
            name = System.getProperty("koha.test.db.name", "koha_test");
            user = System.getProperty("koha.test.db.user", "koha");
            pass = System.getProperty("koha.test.db.pass", "kohapass");
            System.out.println("[PatronImageServiceContainerTest] Usando BD externa "
                    + host + ":" + port + "/" + name);
        } else if (dockerAvailable()) {
            mariaDb = new MariaDBContainer<>("mariadb:10.11")
                    .withDatabaseName("koha_test")
                    .withUsername("koha")
                    .withPassword("kohapass");
            mariaDb.start();
            host = mariaDb.getHost();
            port = mariaDb.getFirstMappedPort();
            name = mariaDb.getDatabaseName();
            user = mariaDb.getUsername();
            pass = mariaDb.getPassword();
            System.out.println("[PatronImageServiceContainerTest] Usando Testcontainers MariaDB");
        } else {
            assumeTrue(false,
                    "Ni BD externa ni Docker disponibles: se omiten las pruebas de integracion JDBC.");
            return;
        }

        // Reproduce el esquema REAL de patronimage capturado de Koha PROD (koha_bul):
        // borrowernumber PK, mimetype varchar(15), imagefile mediumblob.
        // Sin la FK a borrowers para mantener el test autocontenido.
        String jdbcUrl = "jdbc:mariadb://" + host + ":" + port + "/" + name;
        try (Connection conn = java.sql.DriverManager.getConnection(jdbcUrl, user, pass);
             Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS patronimage");
            st.execute(
                "CREATE TABLE patronimage ("
                + "  borrowernumber int(11) NOT NULL,"
                + "  mimetype varchar(15) NOT NULL,"
                + "  imagefile mediumblob NOT NULL,"
                + "  PRIMARY KEY (borrowernumber)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo preparar el esquema de prueba", e);
        }

        provider = new JdbcConnectionProvider(host, port, name, user, pass, 2);
        service = new PatronImageService(provider);
        enabled = true;
    }

    @AfterAll
    static void stopContainer() {
        if (provider != null) {
            provider.close();
        }
        if (mariaDb != null) {
            mariaDb.stop();
        }
    }

    @BeforeEach
    void cleanTable() throws SQLException {
        assumeTrue(enabled);
        try (Connection conn = provider.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("DELETE FROM patronimage");
        }
    }

    @Test
    void upsertInsertsNewRow() {
        byte[] image = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x01};
        service.upsertImage("1001", image, "image/jpeg");

        byte[] stored = service.getImage("1001");
        assertNotNull(stored);
        assertArrayEquals(image, stored);
    }

    @Test
    void upsertOnExistingRowIsIdempotentAndUpdates() {
        byte[] first = new byte[]{1, 2, 3};
        byte[] second = new byte[]{9, 8, 7, 6, 5};

        // Primer upsert: inserta.
        service.upsertImage("2002", first, "image/jpeg");
        assertArrayEquals(first, service.getImage("2002"));

        // Segundo upsert sobre la misma PK: actualiza, no falla con duplicate key.
        service.upsertImage("2002", second, "image/png");
        assertArrayEquals(second, service.getImage("2002"),
                "El upsert sobre fila existente debe actualizar la imagen");

        // Repetir el mismo upsert (idempotencia): sigue sin fallar.
        assertDoesNotThrow(() -> service.upsertImage("2002", second, "image/png"));
        assertArrayEquals(second, service.getImage("2002"));
    }

    @Test
    void getImageReturnsNullWhenNoPhoto() {
        assertNull(service.getImage("3003"), "Patron sin foto debe devolver null");
    }

    @Test
    void deleteImageRemovesExistingRow() {
        service.upsertImage("4004", new byte[]{1, 1, 1}, "image/jpeg");
        assertNotNull(service.getImage("4004"));

        service.deleteImage("4004");
        assertNull(service.getImage("4004"), "Tras delete, la foto debe desaparecer");
    }

    @Test
    void deleteImageOnNonExistentRowIsSuccess() {
        // 0 filas afectadas = exito, no debe lanzar excepcion.
        assertDoesNotThrow(() -> service.deleteImage("999999"));
    }

    @Test
    void upsertHandlesLargerBinaryPayload() {
        byte[] big = new byte[256 * 1024]; // 256 KB
        Arrays.fill(big, (byte) 0x7A);
        service.upsertImage("5005", big, "image/png");

        byte[] stored = service.getImage("5005");
        assertNotNull(stored);
        assertEquals(big.length, stored.length);
        assertArrayEquals(big, stored);
    }

    @Test
    void testConnectionSucceedsAgainstRealDatabase() {
        assertDoesNotThrow(() -> service.testConnection());
    }
}
