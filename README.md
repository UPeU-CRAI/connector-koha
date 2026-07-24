# Conector de Koha para MidPoint

Conector de identidades para **Evolveum MidPoint** que gestiona el ciclo de vida de usuarios (Patrones) y grupos (Categorías de Patrones) en el **Sistema Integrado de Gestión de Bibliotecas (ILS) Koha**. Es un conector **híbrido**: usa la API REST de Koha para los atributos del patrón y un **canal JDBC opcional** para la autorización del patrón (`borrowers.flags` y `user_permissions`, que la API REST de Koha no expone). Está desarrollado siguiendo las mejores prácticas del Identity Connector Framework (ConnId).

**Versión 1.5.0** — Requiere **Koha 25.11+** (usa PATCH para actualizaciones de patrones).

## ✨ Características Principales
* **Gestión completa de Patrones y Categorías**: Operaciones de `Create`, `Search`, `Update` y `Delete` para cuentas y grupos.
* **Canal JDBC para la autorización del patrón**: Provisiona `borrowers.flags` y `user_permissions` directamente en la base de datos de Koha, ya que la API REST no los expone. El canal es opcional (`dbEnabled`), pero si se deshabilita el conector **no** puede provisionar permisos.
* **Sin fotos binarias**: el conector no provisiona blobs de imagen; una foto viaja como URI/URL en un atributo de texto (ver v1.5.0).
* **Arquitectura moderna y desacoplada**: El conector implementa directamente las interfaces de ConnId sin depender de clases base abstractas, lo que resulta en un código más robusto, mantenible y fácil de probar.
* **Autenticación flexible**: Soporte nativo para autenticación **Básica** (usuario/contraseña) y **OAuth2** (Client Credentials).
* **Búsqueda por atributos**: Permite buscar usuarios por UID, `userid`, `email` y `cardnumber` directamente desde MidPoint.
* **Configuración limpia**: Un formulario de configuración en MidPoint que expone únicamente las propiedades necesarias, sin campos heredados innecesarios.
* **Operaciones avanzadas**: Soporte para atributos extendidos (JSON strings), filtros ContainsFilter y StartsWithFilter, paginación con X-Total-Count.
* **Atributo `__ENABLE__` para ciclo de vida**: Control dual de estado mediante `patron_card_lost` y `expiry_date` para operaciones Joiner/Mover/Leaver.
* **17 nuevos campos de Koha 25.x**: `preferred_name`, `pronouns`, `primary_contact_method`, `sms_number`, `middle_name`, `title`, `other_name`, `initials`, `relationship_type`, `sms_provider_id` y campos de dirección alternativa (`altaddress_*`).
* **Categorías de solo lectura**: Las categorías reflejan el comportamiento real de la API de Koha.

## 📋 Requisitos Previos
* **Java** Development Kit (JDK) **8** (lo exige `connector-parent` 1.5.2.0).
* **Apache Maven** 3.6.3 o superior para compilar desde la fuente.
* **Koha 25.11+** y, si se usa el canal JDBC de autorización, acceso de red a la base de datos MariaDB de Koha.

## 🚀 Instalación
1.  **Descargar el conector**: Visita la sección [Releases](https://github.com/UPeU-Infra/connector-koha/releases) y descarga el archivo `.jar` más reciente (p. ej., `connector-koha-1.5.0.jar`).
2.  **Desplegar en MidPoint**: Copia el `.jar` en el directorio de conectores de tu instancia de MidPoint.
    ```bash
    cp connector-koha-1.5.0.jar $MIDPOINT_HOME/var/icf-connectors/
    ```
3.  **Reiniciar MidPoint** para que detecte y cargue el nuevo conector.

## ⚙️ Configuración del Recurso en MidPoint

Al crear un nuevo recurso en MidPoint, el fragmento de `connectorConfiguration` será el siguiente. Adapta los valores a tu entorno.

```xml
<connectorConfiguration>
    <icfc:configurationProperties
        xmlns:icfc="http://midpoint.evolveum.com/xml/ns/public/connector/icf-1/connector-schema-3"
        xmlns:cfg="http://midpoint.evolveum.com/xml/ns/public/connector/icf-1/bundle/connector-koha/com.identicum.connectors.KohaConnector">
        
        <cfg:serviceAddress>http://TU_URL_DE_KOHA</cfg:serviceAddress>
        
        <cfg:authenticationMethodStrategy>OAUTH2</cfg:authenticationMethodStrategy>
        
        <cfg:clientId>TU_CLIENT_ID</cfg:clientId>
        <cfg:clientSecret>
            <t:clearValue>TU_CLIENT_SECRET</t:clearValue>
        </cfg:clientSecret>
        
        <cfg:trustAllCertificates>false</cfg:trustAllCertificates>
        
    </icfc:configurationProperties>
</connectorConfiguration>
```

## 🔐 Canal JDBC para la autorización del patrón

La API REST de Koha **no expone** `borrowers.flags` ni `user_permissions` (los permisos del personal de biblioteca). Para provisionarlos, el conector abre un canal JDBC directo a la base de datos MariaDB de Koha.

> **El conector NO maneja fotos.** Provisionar imágenes binarias desde MidPoint quedó explícitamente fuera de alcance (retirado en v1.5.0): el aprovisionamiento de identidades mueve atributos de identidad, no archivos. Si una foto debe llegar a Koha, se lleva como **URI/URL** en un atributo de texto corriente, no como blob. Hay un test que falla si alguien reintroduce un atributo binario en el esquema.

### Diseño

* El canal JDBC es **opcional**: se activa con `dbEnabled=true`. Con `dbEnabled=false` (valor por defecto) el conector funciona solo con REST.
* **La autorización no admite degradación silenciosa**: si MidPoint pide provisionar `flags`/`user_permissions` y el canal JDBC no está operativo, la operación **falla**. Nunca se informa un falso éxito sobre un permiso que no se persistió.
* La clave es siempre el `borrowernumber` de Koha (= `patron_id` = `__UID__` de ConnId).
* `flags` y `user_permissions` solo se leen si MidPoint los pide explícitamente vía `attributesToGet` (`returnedByDefault=false`).
* `replaceAuthorization()` escribe el bitmask y el conjunto completo de permisos en **una sola transacción**, con `SELECT ... FOR UPDATE` sobre el patrón.
* Usa el pool nativo `MariaDbPoolDataSource` del driver MariaDB (no HikariCP, por conflictos con el classloader aislado de ConnId). **Ojo**: ese `DataSource` no crea un pool propio, sino que lo obtiene del registro estático global `org.mariadb.jdbc.pool.Pools` indexado por configuración. Por eso cada provider fija un `poolName` único: sin él, dos instancias del conector comparten pool y el `dispose()` de una inutiliza a las otras (ver v1.4.1 en el changelog). Tampoco es *lazy*: abre `minPoolSize` conexiones al construirse.

### Configuración JDBC

```xml
<connectorConfiguration>
    <icfc:configurationProperties ...>

        <!-- ... configuración REST ... -->

        <cfg:dbEnabled>true</cfg:dbEnabled>
        <cfg:dbHost>192.168.12.136</cfg:dbHost>
        <cfg:dbPort>3306</cfg:dbPort>
        <cfg:dbName>koha_upeu</cfg:dbName>
        <cfg:dbUser>TU_USUARIO_DB</cfg:dbUser>
        <cfg:dbPassword>
            <t:clearValue>TU_PASSWORD_DB</t:clearValue>
        </cfg:dbPassword>
        <cfg:dbPoolSize>2</cfg:dbPoolSize>

    </icfc:configurationProperties>
</connectorConfiguration>
```

| Propiedad | Tipo | Default | Descripción |
|---|---|---|---|
| `dbEnabled` | boolean | `false` | Habilita el canal JDBC de autorización |
| `dbHost` | String | — | Host del servidor MariaDB de Koha |
| `dbPort` | int | `3306` | Puerto MariaDB |
| `dbName` | String | — | Nombre del esquema de Koha (ej. `koha_upeu`) |
| `dbUser` | String | — | Usuario MariaDB con permisos sobre `borrowers` y `user_permissions` |
| `dbPassword` | GuardedString | — | Contraseña del usuario MariaDB |
| `dbPoolSize` | int | `2` | Tamaño máximo del pool JDBC **por instancia del conector** |

## 🏛️ Arquitectura del Conector

* **KohaConnector.java**: Orquestador principal del conector. Implementa directamente las interfaces de ConnId (Connector, CreateOp, SearchOp, etc.) y coordina la lógica de negocio, incluyendo la rama del canal JDBC para la autorización.

* **KohaConfiguration.java**: Clase de configuración autocontenida que define las propiedades del conector visibles en MidPoint (REST y JDBC). Implementa `org.identityconnectors.framework.spi.Configuration`.

* **KohaAuthenticator.java**: Centraliza la lógica para crear un cliente HTTP pre-autenticado, ya sea con Basic Auth o un token de OAuth2.

* **Paquete `services`**: Contiene las clases que se comunican con los sistemas externos:
  * `PatronService`, `CategoryService` — endpoints de la API REST de Koha.
  * `JdbcConnectionProvider` — gestiona el pool de conexiones JDBC (`MariaDbPoolDataSource`), con `poolName` único por instancia.
  * `PatronPermissionService` — operaciones JDBC sobre `borrowers.flags` y `user_permissions` (lectura y reemplazo transaccional).

* **Paquete `mappers`**: Incluye los transformadores (`PatronMapper`, `CategoryMapper`) que convierten los datos entre el formato de ConnId y el JSON de Koha.

## 🔧 Atributos Extendidos (Extended Attributes)

Los atributos extendidos de Koha se representan como cadenas JSON en el conector. Para agregar atributos extendidos a un patrón, usa el siguiente formato:

```xml
<attribute>
    <name>extended_attributes</name>
    <values>
        <value>{"type":"DNI","value":"12345678"}</value>
        <value>{"type":"CODIGO_ESTUDIANTE","value":"EST-2026-0001"}</value>
    </values>
</attribute>
```

Cada valor es una cadena JSON con dos campos:
- `type`: El tipo de atributo definido en Koha
- `value`: El valor del atributo

En MidPoint, los atributos extendidos se pueden mapear desde una fuente de identidad transformándolos al formato JSON antes de enviarlos al conector.

## 🐛 Troubleshooting

Para un diagnóstico detallado, puedes activar el logging TRACE o DEBUG en MidPoint. Añade la siguiente configuración a tu `logback.xml`:

```xml
<logger name="com.identicum.connectors" level="TRACE"/>
```

Niveles de log recomendados:

INFO: Operaciones generales (por defecto).

DEBUG: Información útil para depurar flujos de operaciones.

TRACE: Máximo nivel de detalle, incluyendo los payloads de las peticiones y respuestas HTTP.

Revisa los logs de MidPoint para ver los mensajes emitidos por el conector.

## 📜 Changelog

### v1.5.0 (2026-07-24)
- **Breaking**: se retira por completo el soporte de **fotos del patrón**. Desaparecen los atributos `photo` y `photo_mimetype`, la clase `PatronImageService` y todas las ramas de lectura/escritura sobre la tabla `patronimage`. Provisionar imágenes binarias desde MidPoint queda fuera de alcance por decisión de arquitectura: el aprovisionamiento de identidades mueve atributos de identidad, no archivos. Una foto puede seguir llegando a Koha como **URI/URL** en un atributo de texto corriente.
- **Change**: el canal JDBC (`dbEnabled`) pasa a servir **exclusivamente** la autorización del patrón (`borrowers.flags` y `user_permissions`), que Koha no expone por REST. `dbEnabled` **no** puede apagarse sin romper los permisos del personal de biblioteca.
- **Tests**: nuevo guardián de política que falla si se reintroduce `photo`/`photo_mimetype` o cualquier atributo `byte[]` en el esquema. Las pruebas de integración del pool JDBC se mueven a `JdbcConnectionProviderContainerTest`.
- **Migración**: antes de desplegar, retirar del `schemaHandling` del recurso los mapeos `ri:photo` y `ri:photo_mimetype`. Si se dejan, MidPoint enviará atributos que el conector ya no declara.

### v1.4.1 (2026-07-24)
- **Fix (crítico)**: aislamiento del pool JDBC. `MariaDbPoolDataSource` no crea un pool propio: delega en el registro **estático global** `org.mariadb.jdbc.pool.Pools`, indexado por la `Configuration` resultante. Dos instancias del conector con idéntica configuración obtenían **el mismo objeto `Pool`**, de modo que el `dispose()` de una destruía el pool de las demás. A partir de ahí toda operación JDBC fallaba de forma permanente con `No connection available within the specified time (option 'connectTimeout': 10,000 ms)`, sin recuperarse ni reiniciando MidPoint. Ahora cada provider genera un `poolName` único, por lo que obtiene un pool propio.
- **Fix**: se eliminan dos pools huérfanos por cada provider. Cada uno de `setUrl()`, `setUser()` y `setPassword()` dispara la creación de un pool; encadenarlos creaba dos pools previos **sin contraseña** (uno con el usuario del sistema operativo), que fallaban autenticación y nunca se cerraban. Las credenciales van ahora en la URL, de modo que se crea un único pool ya autenticado. El driver enmascara el password como `***` en `Configuration.toString()`, así que no aparece en mensajes de excepción.
- **Fix**: `minPoolSize=1` explícito. El driver abre `minPoolSize` conexiones de forma *eager* al construir el pool y por defecto `minPoolSize == maxPoolSize`; como ConnId instancia un conector por operación, el default abría `dbPoolSize` conexiones **en cada operación**.
- **Fix**: `registerJmxPool=false`. Con `poolName` único, cada pool registraría un MBean distinto; un `dispose()` perdido los dejaría registrados indefinidamente.
- **Fix**: la contraseña se codifica para la URL — con `&`, `=` o `#` se rompía el parseo y degeneraba en un fallo de autenticación difícil de diagnosticar.
- **Fix**: `dispose()` anula `httpAdapter`; ConnId lo invoca dos veces cuando `init()` falla (una desde el `catch` del propio `init()` y otra desde su `finally`).
- **Tests**: regresión del aislamiento de pools contra MariaDB real, y cobertura de contraseñas con caracteres reservados de URL.
- **Nota**: `KohaConnector` **no** implementa `PoolableConnector`, por lo que MidPoint crea una instancia nueva con `init()`+`dispose()` en cada operación de API. Es la razón de que este fallo fuera tan agresivo, y sigue siendo una ineficiencia estructural pendiente de evaluar.

### v1.3.0 (2026-05-21)
- **Feature**: Canal JDBC opcional para provisionar la foto del patrón en la tabla `patronimage` de Koha (la API REST de Koha 25.11 no tiene endpoint de imagen). Conector híbrido: REST conserva los ~48 atributos del patrón intactos, JDBC solo para la foto. Un único JAR.
- **Feature**: Nuevos atributos de patrón `photo` (`byte[]`) y `photo_mimetype` (`String`), ambos `returnedByDefault=false` y excluidos del payload JSON REST.
- **Feature**: 7 nuevas propiedades de configuración para el canal JDBC: `dbEnabled`, `dbHost`, `dbPort`, `dbName`, `dbUser`, `dbPassword`, `dbPoolSize`.
- **Feature**: Degradación elegante — si la base de datos no está disponible, el CRUD sigue operativo en modo REST; solo `Test Connection` falla cuando `dbEnabled=true`.
- **Feature**: Clases nuevas `JdbcConnectionProvider` (pool nativo `MariaDbPoolDataSource`) y `PatronImageService` (upsert idempotente con `ON DUPLICATE KEY UPDATE`, delete, get, test).
- **Change**: Nueva dependencia MariaDB Connector/J 3.5.3, empaquetada en el JAR del conector.
- **Tests**: Suite de pruebas para el canal JDBC con mocks y con MariaDB real (Testcontainers o base de datos externa).

### v1.2.2 (2026-05-21)
- **Fix**: Fallback search by cardnumber when userid lookup returns empty results (PatronService.java)

### v1.2.1
- Version bump (superseded by 1.2.2)

### v1.2.0 (2026-04-16)
- **BREAKING**: Requiere Koha 25.11+ (usa PATCH para actualizaciones de patrones)
- **Feature**: Atributo operacional `__ENABLE__` para ciclo de vida de MidPoint (mecanismo dual: patron_card_lost + expiry_date)
- **Feature**: Serialización de atributos extendidos como cadenas JSON
- **Feature**: 17 nuevos atributos de patrón (campos Koha 25.x + dirección alternativa)
- **Feature**: Soporte para filtros ContainsFilter y StartsWithFilter
- **Feature**: Paginación con X-Total-Count para detección precisa de páginas
- **Feature**: Mejora de mensajes de error DELETE 409 (diferenciación de errores)
- **Change**: Las categorías ahora son de solo lectura (refleja el comportamiento real de la API de Koha)
- **Change**: `date_enrolled` ahora se puede establecer en la creación
- **Fix**: Guardias de logging — ningún PII en logs de nivel INFO
- **Fix**: Validación de configuración para contraseña de autenticación BASIC
- **Fix**: KohaResource.xml actualizado a v1.2.0 con asignaciones completas

### v1.0.1 (23 de julio de 2025)
- REFACTOR: Se ha refactorizado completamente el conector para eliminar la herencia de clases base (AbstractRestConnector, AbstractRestConfiguration). Ahora implementa las interfaces de ConnId directamente, resultando en un código más limpio y autocontenido.
- REFACTOR: La clase KohaConfiguration ahora es independiente y cumple con el contrato de la interfaz Configuration de ConnId, solucionando errores de compilación y mostrando un formulario limpio en MidPoint.
- CHORE: Se mejoró el archivo de mensajes (Messages.properties) para usar caracteres UTF-8 directamente, aumentando su legibilidad.
- FIX: Corregidos errores de compilación y pruebas de integración para alinearse con la nueva arquitectura.
- DOCS: Actualizado el README.md para reflejar la nueva arquitectura y las mejoras.

### v1.0.0
- Lanzamiento inicial del conector.
- Soporte completo para operaciones CRUD de Patrones de Koha.
- Implementación de autenticación Básica y OAuth2.

## ⚖️ Licencia
Este proyecto está bajo la [Licencia Apache 2.0](LICENSE).

## 🤝 Contribuciones
Las contribuciones son bienvenidas. Para cambios mayores, abre primero un issue para discutir lo que deseas modificar.
