# Documento de Requisitos del Producto (PRD)

## Resumen Ejecutivo
Este proyecto implementa un conector para **Evolveum MidPoint** que gestiona usuarios y grupos en el sistema **Koha ILS** por medio de su API REST. El objetivo del conector es sincronizar el ciclo de vida de las identidades (patrones y categorías de patrones) entre MidPoint y Koha, apoyándose en el framework **ConnId**.

## Alcance
- Sincronización bidireccional de usuarios (*patrons*) y grupos (*patron categories*).
- Compatibilidad con autenticación **BASIC** y **OAuth2**.
- Búsqueda avanzada de usuarios por UID, nombre de usuario, correo y número de tarjeta.
- Configuración simplificada en MidPoint mediante un formulario de propiedades mínimo.

## Requerimientos Funcionales
1. **Gestión de Patrones (Usuarios)**
   - Crear, actualizar, buscar y eliminar patrones en Koha.
   - Mapear atributos entre ConnId y la estructura JSON de Koha usando `PatronMapper`.
2. **Gestión de Categorías (Grupos)**
   - Crear, actualizar, buscar y eliminar categorías de patrones.
   - Utilizar `CategoryMapper` para transformar datos entre ambos sistemas.
3. **Autenticación**
   - Selección de estrategia de autenticación mediante la propiedad `authenticationMethodStrategy`.
   - Soporte para BASIC (`username` y `password`) y OAuth2 (`clientId` y `clientSecret`).
4. **Pruebas de Conectividad**
   - El método `test()` del conector debe validar la configuración, obtener el esquema y realizar una consulta básica.

## Requerimientos No Funcionales
- Compatibilidad con Java 8, 11 y 17.
- Uso del framework ConnId sin dependencias externas complejas.
- Registro detallado con niveles INFO, DEBUG y TRACE.
- Manejo robusto de errores y excepciones de la API de Koha.

## Componentes Clave
- **KohaConnector**: implementa las operaciones de ConnId y orquesta las llamadas a los servicios.
- **KohaConfiguration**: define y valida las propiedades configurables del conector.
- **Servicios** (`PatronService`, `CategoryService`): gestionan las llamadas HTTP a la API REST de Koha.
- **Mappers** (`PatronMapper`, `CategoryMapper`): transforman atributos entre Koha y ConnId.

## Flujos Principales
1. **Creación de Patron**
   1. MidPoint llama a `KohaConnector#create` con los atributos del usuario.
   2. `PatronMapper` genera el JSON correspondiente.
   3. `PatronService` envía un `POST` a `/api/v1/patrons` y devuelve el `patron_id` de Koha.
2. **Actualización de Patron**
   1. MidPoint invoca `KohaConnector#update` con el UID y los cambios.
   2. El servicio recupera el registro actual, aplica cambios permitidos y ejecuta un `PUT`.
3. **Búsqueda de Patron**
   1. MidPoint ejecuta `KohaConnector#executeQuery` con un filtro (`KohaFilter`).
   2. `PatronService` compone la URL con los parámetros de búsqueda y pagina los resultados.
4. **Autenticación OAuth2**
   1. `KohaAuthenticator` solicita un token con `clientId` y `clientSecret`.
   2. El token se reutiliza hasta que expira, añadiéndose como `Bearer` en cada petición.

## Métricas de Éxito
- **Tiempo de provisión** de cuentas en Koha inferior a 2 segundos por operación.
- **Cobertura de pruebas** unitaria mínima del 80 % en módulos de mapeo y servicios.
- **Confiabilidad**: manejo correcto de errores de la API y reintentos en fallas temporales.

## Fuera de Alcance
- Sincronización de préstamos o reservas de Koha.
- Gestión de inventario o catálogo de la biblioteca.

