# Conector de Koha para MidPoint

Conector de identidades para **Evolveum MidPoint** que gestiona el ciclo de vida de usuarios (Patrones) y grupos (Categorías de Patrones) en el **Sistema Integrado de Gestión de Bibliotecas (ILS) Koha**. Utiliza la API REST de Koha y está desarrollado siguiendo las mejores prácticas del Identity Connector Framework (ConnId).

## ✨ Características Principales
* **Gestión completa de Patrones y Categorías**: Operaciones de `Create`, `Search`, `Update` y `Delete` para cuentas y grupos.
* **Arquitectura moderna y desacoplada**: El conector implementa directamente las interfaces de ConnId sin depender de clases base abstractas, lo que resulta en un código más robusto, mantenible y fácil de probar.
* **Autenticación flexible**: Soporte nativo para autenticación **Básica** (usuario/contraseña) y **OAuth2** (Client Credentials).
* **Búsqueda por atributos**: Permite buscar usuarios por UID, `userid`, `email` y `cardnumber` directamente desde MidPoint.
* **Configuración limpia**: Un formulario de configuración en MidPoint que expone únicamente las propiedades necesarias, sin campos heredados innecesarios.

## 📋 Requisitos Previos
* **Java** Development Kit (JDK) **8**, **11** o **17** (LTS).
* **Apache Maven** 3.6.3 o superior para compilar desde la fuente.

## 🚀 Instalación
1.  **Descargar el conector**: Visita la sección [Releases](https://github.com/UPeU-CRAI/connector-koha/releases) y descarga el archivo `.jar` más reciente (p. ej., `connector-koha-1.0.1.jar`).
2.  **Desplegar en MidPoint**: Copia el `.jar` en el directorio de conectores de tu instancia de MidPoint.
    ```bash
    cp connector-koha-1.0.1.jar $MIDPOINT_HOME/var/icf-connectors/
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

## 📜 Historial de Versiones
### v1.0.1 (23 de julio de 2025)
REFACTOR: Se ha refactorizado completamente el conector para eliminar la herencia de clases base (AbstractRestConnector, AbstractRestConfiguration). Ahora implementa las interfaces de ConnId directamente, resultando en un código más limpio y autocontenido.

REFACTOR: La clase KohaConfiguration ahora es independiente y cumple con el contrato de la interfaz Configuration de ConnId, solucionando errores de compilación y mostrando un formulario limpio en MidPoint.

CHORE: Se mejoró el archivo de mensajes (Messages.properties) para usar caracteres UTF-8 directamente, aumentando su legibilidad.

FIX: Corregidos errores de compilación y pruebas de integración para alinearse con la nueva arquitectura.

DOCS: Actualizado el README.md para reflejar la nueva arquitectura y las mejoras.

### v1.0.0
- Lanzamiento inicial del conector.
- Soporte completo para operaciones CRUD de Patrones de Koha.
- Implementación de autenticación Básica y OAuth2.

## ⚖️ Licencia
Este proyecto está bajo la [Licencia Apache 2.0](LICENSE).

## 🤝 Contribuciones
Las contribuciones son bienvenidas. Para cambios mayores, abre primero un issue para discutir lo que deseas modificar.
