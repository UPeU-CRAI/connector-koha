# Conector de Koha para Midpoint

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Midpoint Version](https://img.shields.io/badge/Midpoint-4.4%2B-orange.svg)](https://evolveum.com/midpoint/)
[![ConnId Version](https://img.shields.io/badge/ConnId-1.5-brightgreen.svg)](https://connid.tirasa.net/)

Conector de identidades para **Evolveum Midpoint** que permite la gestión del ciclo de vida de usuarios (Patrones) en el **Sistema Integrado de Gestión de Bibliotecas (ILS) Koha**.

Este conector utiliza la API REST de Koha y ha sido desarrollado siguiendo las mejores prácticas del Identity Connector Framework (ConnId).

## ✨ Características Principales

-   **Gestión Completa de Patrones:** Soporte para operaciones de Creación (`Create`), Lectura/Búsqueda (`Search`), Actualización (`Update`) y Eliminación (`Delete`).
-   **Autenticación Flexible:** Compatibilidad con autenticación **Básica** (usuario/contraseña) y **OAuth2** (Client Credentials) para una integración segura.
-   **Arquitectura Robusta y Mejorada:** El código sigue un diseño modular que separa responsabilidades (autenticación, servicios, mapeo de datos) y ha sido refactorizado para mejorar la robustez, el manejo de errores y la mantenibilidad.
-   **Búsqueda por Atributos:** Permite buscar usuarios en Koha por UID, `userid`, `email` y `cardnumber` directamente desde Midpoint.

## 🚀 Instalación

1.  **Descargar el Conector:** Ve a la sección de **[Releases](https://github.com/UPeU-CRAI/connector-koha/releases)** de este repositorio y descarga el archivo `.jar` de la última versión (ej. `connector-koha-1.0.0.jar`).
2.  **Desplegar en Midpoint:** Copia el archivo `.jar` descargado en el directorio de conectores de tu instancia de Midpoint:
    ```bash
    cp connector-koha-1.0.0.jar $MIDPOINT_HOME/var/icf-connectors/
    ```
3.  **Reiniciar Midpoint:** Reinicia el servicio de Midpoint para que detecte el nuevo conector.

## ⚙️ Configuración del Recurso en Midpoint

Una vez instalado, puedes crear un nuevo recurso en Midpoint. Aquí tienes un ejemplo de la sección `<connectorConfiguration>` que debes usar.

```xml
<connectorConfiguration>
    <icfc:configurationProperties xmlns:icfc="[http://midpoint.evolveum.com/xml/ns/public/connector/icf-1/connector-schema-3](http://midpoint.evolveum.com/xml/ns/public/connector/icf-1/connector-schema-3)"
                                  xmlns:cfg="[http://midpoint.evolveum.com/xml/ns/public/connector/icf-1/bundle/connector-koha/com.identicum.connectors.KohaConnector](http://midpoint.evolveum.com/xml/ns/public/connector/icf-1/bundle/connector-koha/com.identicum.connectors.KohaConnector)">
        
        <cfg:serviceAddress>http://TU_URL_DE_KOHA</cfg:serviceAddress>

        <cfg:authMethod>OAUTH2</cfg:authMethod>
        
        <cfg:clientId>TU_CLIENT_ID</cfg:clientId>
        <cfg:clientSecret>
            <t:clearValue>TU_CLIENT_SECRET</t:clearValue>
        </cfg:clientSecret>

        <cfg:trustAllCertificates>false</cfg:trustAllCertificates>
        
    </icfc:configurationProperties>
</connectorConfiguration>
```

Para una guía completa sobre cómo mapear los atributos del **Esquema de Extensión UPeU** a los atributos de este conector, consulta la documentación del esquema.

## 🏛️ Arquitectura del Conector

El código fuente del conector sigue una arquitectura modular para separar responsabilidades:
-   **`KohaConnector.java`**: Actúa como el orquestador principal que implementa las interfaces de ConnId.
-   **`KohaAuthenticator.java`**: Centraliza la lógica de autenticación.
-   **Paquete `services`**: Gestiona la comunicación con los endpoints específicos de la API de Koha, extendiendo funcionalidades de una clase base abstracta (`AbstractKohaService.java`) que maneja la lógica HTTP común y el manejo de errores mejorado.
-   **Paquete `mappers`**: Se encarga de la transformación de datos entre Midpoint y el formato JSON de Koha.

## 🐛 Troubleshooting

Para obtener información de diagnóstico detallada, puedes habilitar el logging `TRACE` o `DEBUG` para este conector en Midpoint. Esto es especialmente útil si encuentras problemas durante la configuración o la ejecución de operaciones.

Añade la siguiente configuración a tu archivo de logging de Midpoint (generalmente `logback.xml` o un archivo similar referenciado en la configuración de logging de Midpoint):

```xml
<logger name="com.identicum.connectors" level="TRACE"/>
```

Opciones de nivel de log:
-   `ERROR`: Solo errores críticos que impiden el funcionamiento.
-   `WARN`: Advertencias sobre situaciones potencialmente problemáticas.
-   `INFO`: Mensajes informativos generales sobre el flujo de operaciones (por defecto para muchas operaciones del conector).
-   `DEBUG`: Información detallada útil para depurar el flujo de control y las solicitudes/respuestas básicas.
-   `TRACE`: El nivel más detallado, incluye payloads de solicitud/respuesta, transformaciones de atributos, etc. Puede generar mucho output.

Revisa los logs de Midpoint para ver los mensajes detallados del conector. Esto te ayudará a ti o a los desarrolladores a entender qué está sucediendo.

## 📜 Licencia

Este proyecto está bajo la Licencia Apache 2.0. Consulta el archivo `LICENSE` para más detalles.

## 🤝 Contribuciones

Las contribuciones son bienvenidas. Para cambios mayores, por favor, abre un "issue" primero para discutir lo que te gustaría cambiar.

