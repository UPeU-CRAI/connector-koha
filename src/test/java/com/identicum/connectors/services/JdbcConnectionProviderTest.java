package com.identicum.connectors.services;

import org.identityconnectors.framework.common.exceptions.ConnectionFailedException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas de {@link JdbcConnectionProvider} que no requieren una base de datos
 * real.
 */
class JdbcConnectionProviderTest {

    @Test
    void constructorSucceedsWithoutOpeningConnections() {
        JdbcConnectionProvider provider = new JdbcConnectionProvider(
                "127.0.0.1", 3306, "koha_test", "user", "pass", 2);
        assertNotNull(provider.getDataSource());
        provider.close();
    }

    /**
     * Regresion del bug de v1.4.0: el registro estatico de pools del driver
     * ({@code org.mariadb.jdbc.pool.Pools}) indexa por {@code Configuration}, de modo que
     * dos providers con identica configuracion compartian el MISMO pool. El
     * {@code close()} de uno (via {@code KohaConnector.dispose()}) destruia el pool del
     * otro, que quedaba fallando de forma permanente con
     * "No connection available within the specified time".
     *
     * <p>Cada provider debe tener un {@code poolName} propio para que eso no ocurra.</p>
     */
    @Test
    void eachProviderGetsItsOwnPoolName() {
        JdbcConnectionProvider a = new JdbcConnectionProvider(
                "127.0.0.1", 3306, "koha_test", "user", "pass", 2);
        JdbcConnectionProvider b = new JdbcConnectionProvider(
                "127.0.0.1", 3306, "koha_test", "user", "pass", 2);
        try {
            assertNotEquals(a.getPoolName(), b.getPoolName(),
                    "Dos providers con identica config deben tener poolName distinto; "
                    + "si coinciden comparten pool y el dispose() de uno rompe al otro.");
            assertNotSame(a.getDataSource(), b.getDataSource());
        } finally {
            a.close();
            b.close();
        }
    }

    // El escenario "cerrar A no debe romper B" requiere una BD real y vive en
    // PatronImageServiceContainerTest#closingOneProviderDoesNotBreakAnother: contra un host
    // inalcanzable, el driver devuelve el mismo "No connection available" por otra causa,
    // asi que aqui no probaria nada.

    /**
     * Una contrasena con caracteres reservados de URL debe seguir funcionando: si no se
     * codifica, rompe el parseo de la URL JDBC y degenera en un fallo de autenticacion.
     */
    @Test
    void passwordWithUrlReservedCharactersDoesNotBreakConstruction() {
        JdbcConnectionProvider provider = new JdbcConnectionProvider(
                "127.0.0.1", 3306, "koha_test", "user", "p@ss&w=rd#1 2+3/4", 2);
        try {
            assertNotNull(provider.getDataSource());
        } finally {
            provider.close();
        }
    }

    @Test
    void testConnectionFailsAgainstUnreachableHost() {
        // Puerto improbable de estar abierto: la conexion debe fallar
        // y traducirse a ConnectionFailedException, no a un stacktrace crudo.
        JdbcConnectionProvider provider = new JdbcConnectionProvider(
                "127.0.0.1", 1, "koha_test", "user", "pass", 1);
        try {
            assertThrows(ConnectionFailedException.class, provider::testConnection);
        } finally {
            provider.close();
        }
    }

    @Test
    void closeIsIdempotent() {
        JdbcConnectionProvider provider = new JdbcConnectionProvider(
                "127.0.0.1", 3306, "koha_test", "user", "pass", 2);
        assertDoesNotThrow(provider::close);
        // Invocar close() de nuevo no debe lanzar excepcion.
        assertDoesNotThrow(provider::close);
    }
}
