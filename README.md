# Conector de Koha para MidPoint

Conector de identidades para **Evolveum MidPoint** que gestiona el ciclo de vida de usuarios (Patrones) y grupos (Categorías de Patrones) en el **Sistema Integrado de Gestión de Bibliotecas (ILS) Koha**. Utiliza la API REST de Koha y está desarrollado siguiendo las mejores prácticas del Identity Connector Framework (ConnId).

**Versión 1.2.0** — Requiere **Koha 25.11+** (usa PATCH para actualizaciones de patrones).

## ✨ Características Principales
* **Gestión completa de Patrones y Categorías**: Operaciones de `Create`, `Search`, `Update` y `Delete` para cuentas y grupos.
* **Arquitectura moderna y desacoplada**: El conector implementa directamente las interfaces de ConnId sin depender de clases base abstractas, lo que resulta en un código más robusto, mantenible y fácil de probar.
* **Autenticación flexible**: Soporte nativo para autenticación **Básica** (usuario/contraseña) y **OAuth2** (Client Credentials).
* **Búsqueda por atributos**: Permite buscar usuarios por UID, `userid`, `email` y `cardnumber` directamente desde MidPoint.
* **Configuración limpia**: Un formulario de configuración en MidPoint que expone únicamente las propiedades necesarias, sin campos heredados innecesarios.
* **Operaciones avanzadas**: Soporte para atributos extendidos (JSON strings), filtros ContainsFilter y StartsWithFilter, paginación con X-Total-Count.
* **Atributo `__ENABLE__` para ciclo de vida**: Control dual de estado mediante `patron_card_lost` y `expiry_date` para operaciones Joiner/Mover/Leaver.
* **17 nuevos campos de Koha 25.x**: `preferred_name`, `pronouns`, `primary_contact_method`, `sms_number`, `middle_name`, `title`, `other_name`, `initials`, `relationship_type`, `sms_provider_id` y campos de dirección alternativa (`altaddress_*`).
* **Categorías de solo lectura**: Las categorías reflejan el comportamiento real de la API de Koha.

## 📋 Requisitos Previos
* **Java** Development Kit (JDK) **8**, **11** o **17** (LTS).
* **Apache Maven** 3.6.3 o superior para compilar desde la fuente.

## 🚀 Instalación
1.  **Descargar el conector**: Visita la sección [Releases](https://github.com/UPeU-CRAI/connector-koha/releases) y descarga el archivo `.jar` más reciente (p. ej., `connector-koha-1.2.0.jar`).
2.  **Desplegar en MidPoint**: Copia el `.jar` en el directorio de conectores de tu instancia de MidPoint.
    ```bash
    cp connector-koha-1.2.0.jar $MIDPOINT_HOME/var/icf-connectors/
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

## 🏛️ Arquitectura del Conector

* **KohaConnector.java**: Orquestador principal del conector. Implementa directamente las interfaces de ConnId (Connector, CreateOp, SearchOp, etc.) y coordina la lógica de negocio.

* **KohaConfiguration.java**: Clase de configuración autocontenida que define las propiedades del conector visibles en MidPoint. Implementa `org.identityconnectors.framework.spi.Configuration`.

* **KohaAuthenticator.java**: Centraliza la lógica para crear un cliente HTTP pre-autenticado, ya sea con Basic Auth o un token de OAuth2.

* **Paquete `services`**: Contiene las clases (`PatronService`, `CategoryService`) que se comunican con los endpoints de la API REST de Koha.

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
