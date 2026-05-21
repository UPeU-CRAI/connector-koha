package com.identicum.connectors.services;

import org.identityconnectors.framework.common.exceptions.ConnectionFailedException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas de {@link JdbcConnectionProvider} que no requieren una base de datos
 * real. La construccion del {@code MariaDbPoolDataSource} es lazy: no abre
 * conexiones hasta que se solicita una, por lo que estas pruebas son rapidas.
 */
class JdbcConnectionProviderTest {

    @Test
    void constructorSucceedsWithoutOpeningConnections() {
        // MariaDbPoolDataSource no abre conexiones en la construccion.
        JdbcConnectionProvider provider = new JdbcConnectionProvider(
                "127.0.0.1", 3306, "koha_test", "user", "pass", 2);
        assertNotNull(provider.getDataSource());
        provider.close();
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
