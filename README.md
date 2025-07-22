# Conector de Koha para MidPoint

Conector de identidades para **Evolveum MidPoint** que gestiona el ciclo de vida de usuarios (Patrones) en el **Sistema Integrado de Gestión de Bibliotecas (ILS) Koha**. Utiliza la API REST de Koha y está desarrollado siguiendo las mejores prácticas del Identity Connector Framework (ConnId).

## ✨ Características Principales
- **Gestión completa de Patrones**: operaciones de `Create`, `Search`, `Update` y `Delete`.
- **Autenticación flexible**: soporte de autenticación **Básica** (usuario/contraseña) y **OAuth2** (Client Credentials).
- **Arquitectura robusta y modular**: código refactorizado para mayor robustez, manejo de errores y mantenibilidad.
- **Búsqueda por atributos**: por UID, `userid`, `email` y `cardnumber` directamente desde MidPoint.

## 📋 Requisitos Previos
- **Java** Development Kit (JDK) **8**, **11** o **17** (LTS). Con Mockito ≥5.18 y Byte Buddy ≥1.17 es posible usar Java 24; de lo contrario es recomendable limitarse a JDK 8–17.
- **Apache Maven** 3.6.3 o superior.

## 🚀 Instalación
1. **Descargar el conector**: visita la sección [Releases](https://github.com/UPeU-CRAI/connector-koha/releases) y descarga el archivo `.jar` más reciente (por ejemplo, `connector-koha-1.0.1.jar`).
2. **Desplegar en MidPoint**: copia el `.jar` en el directorio de conectores de tu instancia de MidPoint.
   ```bash
   cp connector-koha-1.0.1.jar $MIDPOINT_HOME/var/icf-connectors/
   ```
3. **Reiniciar MidPoint** para detectar el nuevo conector.

## ⚙️ Configuración del Recurso en MidPoint
Al crear un recurso en MidPoint, utiliza la siguiente sección de `connectorConfiguration`:
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
- **`KohaConnector.java`**: orquestador principal que implementa las interfaces de ConnId.
- **`KohaAuthenticator.java`**: centraliza la lógica de autenticación.
- **Paquete `services`**: comunicación con la API REST de Koha y manejo HTTP común (clase base `AbstractKohaService.java`).
- **Paquete `mappers`**: transformación de datos entre MidPoint y el formato JSON de Koha.

## 🐛 Troubleshooting
Para un diagnóstico detallado puedes activar el logging `TRACE` o `DEBUG` en MidPoint. Añade la siguiente configuración a tu `logback.xml` (o archivo de logging equivalente):
```xml
<logger name="com.identicum.connectors" level="TRACE"/>
```
Opciones de nivel de log:
- `ERROR`: solo errores críticos.
- `WARN`: advertencias de posibles problemas.
- `INFO`: mensajes informativos generales.
- `DEBUG`: información detallada útil para depurar.
- `TRACE`: máximo nivel de detalle (incluye payloads de solicitud/respuesta).

Revisa los logs de MidPoint para ver los mensajes emitidos por el conector.

## 📜 Historial de Versiones
### v1.0.1 (08 de julio de 2025)
- **FIX:** se corrigieron errores de compilación y pruebas.
- **FIX:** se ajustó la configuración de pruebas (`KohaConnectorIntegrationTest`) para usar inyección de dependencias con Mockito.
- **CHORE:** se actualizó la configuración de compilación para compatibilidad con JDK 17.
- **DOCS:** se añadió la sección de requisitos previos en este README.

### v1.0.0
- Lanzamiento inicial del conector.
- Soporte completo para operaciones CRUD de Patrones de Koha.
- Implementación de autenticación Básica y OAuth2.

## ⚖️ Licencia
Este proyecto está bajo la [Licencia Apache 2.0](LICENSE).

## 🤝 Contribuciones
Las contribuciones son bienvenidas. Para cambios mayores, abre primero un issue para discutir lo que deseas modificar.
