# Koha Connector v1.2.0 — Upgrade para Koha 25.11.03 + MidPoint IGA UPeU

**Slug:** koha-connector-v1.2-upgrade
**Author:** Claude Code
**Date:** 2026-04-16
**Branch:** preflight/koha-connector-v1.2-upgrade
**Related:** connector-koha v1.1.0, MidPoint 4.9.5, Koha 25.11.03, UPeU IGA

---

## 1) Intent & Assumptions

**Task brief:** Mejorar el conector ConnId MidPoint-Koha de v1.1.0 a v1.2.0 para: (a) compatibilidad completa con la API REST de Koha 25.11.03, (b) soporte de lifecycle completo para MidPoint (Joiner/Mover/Leaver), (c) mejora de calidad de codigo (logging, paginacion, tests), y (d) KohaResource.xml funcional para el proyecto IGA de UPeU.

**Assumptions:**
- El conector se mantiene en Java 8 como target de compilacion (compatibilidad maxima con MidPoint 4.9.x)
- Se mantiene la arquitectura actual (capas: Connector → Service → Mapper) — no se reescribe
- La API de Koha 25.11.03 usa PATCH para update de patrons (breaking change vs 23.x que usaba PUT)
- Patron Categories en Koha 25.11 solo tienen endpoint GET (listado) — no hay CRUD individual
- El conector conecta a UNA instancia Koha. Multi-instancia se resuelve con multiples recursos MidPoint
- OAuth2 Client Credentials es el metodo de auth preferido para produccion
- MidPoint 4.9.5 es la version target (no 4.10 aun)

**Out of scope:**
- SyncOp/LiveSync (delta sync desde Koha hacia MidPoint) — requiere evento webhook o polling, se deja para v2.0
- Gestion de debarments/restricciones (campo `restricted` es read-only en la API de Koha)
- Endpoints de holds, checkouts, account (datos operacionales, no de identidad)
- Migracion a Java 11+ source/target (se mantiene Java 8)
- Unificacion de las 4 instancias Koha en una sola (decision de infra, no del conector)

---

## 2) Pre-reading Log

- `pom.xml`: v1.1.0, Java 8, parent evolveum connector-parent 1.5.2.0, httpclient 4.5.14, org.json:20250517
- `KohaConnector.java` (362 lines): Implementa CreateOp, UpdateOp, SchemaOp, SearchOp, DeleteOp, TestOp. Schema con AtomicReference cache. Update hace GET+PUT merge.
- `KohaConfiguration.java` (165 lines): 7 propiedades. validate() no verifica password en BASIC.
- `KohaAuthenticator.java` (204 lines): Basic + OAuth2 con token cache synchronized+volatile. trustAllCertificates configurable.
- `KohaFilterTranslator.java` (65 lines): Solo EqualsFilter sobre UID, Name, email, cardnumber.
- `KohaFilter.java` (37 lines): 4 campos: byUid, byName, byEmail, byCardNumber.
- `PatronService.java` (142 lines): CRUD con HttpPut para update. Paginacion do-while sin X-Total-Count. searchPatrons maneja JSON array o object.
- `CategoryService.java` (155 lines): CRUD completo (POST/PUT/DELETE) — pero la API de Koha 25.11 solo soporta GET para categories.
- `AbstractKohaService.java` (277 lines): Retry exponencial 3 intentos. processResponseErrors mapea 400/401/403/404/409/5xx. Gzip support. URL encoding.
- `PatronMapper.java` (246 lines): 35 atributos en ATTRIBUTE_METADATA_MAP. extended_attributes como MULTIVALUED String sin serializacion especial.
- `CategoryMapper.java`: 7 atributos. Falta categorycode.
- `BaseMapper.java` (124 lines): Conversion de tipos. Fechas con ISO_LOCAL_DATE. No maneja JSONArray de objetos anidados (extended_attributes).
- `KohaResource.xml`: connectorVersion hardcoded 1.0.1 (deberia ser 1.1.0+). Solo 2 outbound mappings (name, surname). Capabilities declara activation pero no hay mapping.
- `README.md`: Documentacion completa de instalacion y configuracion.
- `PRD.md`: Requisitos funcionales y no funcionales. Target cobertura 80%.
- `upeu-ops/context/koha/10-referencia-mapeo-oracle-koha-midpoint.md`: Guia de mapeo Oracle→Koha→MidPoint
- `upeu-ops/context/koha/11-pendientes-datos-patrons.md`: Campos pendientes, categorias sin alinear entre sedes

---

## 3) Codebase Map

**Primary components/modules:**
- `KohaConnector.java` — Orquestador ConnId, punto de entrada
- `KohaConfiguration.java` — Propiedades configurables
- `KohaAuthenticator.java` — Creacion de HttpClient autenticado
- `services/PatronService.java` — CRUD + search de patrons via REST
- `services/CategoryService.java` — CRUD + search de categories via REST
- `services/AbstractKohaService.java` — HTTP base con retry, error mapping, gzip
- `services/HttpClientAdapter.java` — Interfaz para testabilidad
- `services/DefaultHttpClientAdapter.java` — Implementacion real del adapter
- `mappers/PatronMapper.java` — Transformacion ConnId ↔ JSON para patrons
- `mappers/CategoryMapper.java` — Transformacion ConnId ↔ JSON para categories
- `mappers/BaseMapper.java` — Conversion generica de tipos
- `model/AttributeMetadata.java` — Descriptor de atributo (tipo, flags)
- `KohaFilter.java` — Objeto filtro para busquedas
- `KohaFilterTranslator.java` — Traduce filtros ConnId a KohaFilter

**Shared dependencies:**
- Apache HttpClient 4.5.14 (comunicacion HTTP)
- org.json:20250517 (parsing JSON)
- ConnId framework 1.5.2.0 (interfaces del conector)
- Evolveum connector-parent 1.5.2.0 (build)

**Data flow:**
```
MidPoint → ConnId Framework → KohaConnector
  → KohaFilterTranslator (traduce filtros)
  → PatronMapper.buildPatronJson() (convierte atributos ConnId a JSON)
  → PatronService.createPatron/updatePatron/etc. (HTTP request)
  → AbstractKohaService.callRequestWithEntity() (retry + error handling)
  → KohaAuthenticator (agrega auth header al HttpClient)
  → Koha REST API /api/v1/patrons
  → Respuesta JSON
  → PatronMapper.convertJsonToPatronObject() (convierte JSON a ConnId)
  → MidPoint
```

**Potential blast radius:**
- `PatronService.updatePatron()` — cambio PUT→PATCH afecta toda actualizacion
- `CategoryService` — eliminar CRUD inexistente afecta schema y operaciones
- `PatronMapper.ATTRIBUTE_METADATA_MAP` — agregar atributos afecta schema, create, update, search
- `AbstractKohaService` — cambios en callRequest afectan todos los servicios
- `KohaResource.xml` — cambios afectan el recurso en MidPoint (importacion necesaria)

---

## 4) Root Cause Analysis

No aplica — este no es un bug fix, es una mejora evolutiva.

Sin embargo, hay **problemas latentes** que constituyen bugs potenciales:

### P1: PUT vs PATCH (potencial breaking change)
- **Evidencia:** `PatronService.java:64` usa `HttpPut` para `updatePatron()`
- **API Koha 25.11:** El spec OpenAPI define `PATCH /patrons/{patron_id}` para updates
- **Riesgo:** Si Koha 25.11.03 rechaza PUT, todas las actualizaciones de patrons fallarian con 405 Method Not Allowed
- **Nota:** Koha podria seguir aceptando PUT por retrocompatibilidad — necesita verificacion

### P2: CategoryService expone CRUD que la API no soporta
- **Evidencia:** `CategoryService.java:64-77` tiene createCategory(), updateCategory(), deleteCategory()
- **API Koha 25.11:** Solo `GET /patron_categories` existe
- **Riesgo:** Llamadas a create/update/delete de categories fallarian con 404/405

### P3: extended_attributes sin serializacion
- **Evidencia:** `PatronMapper.java:116` define extended_attributes como MULTIVALUED String
- **Evidencia:** `BaseMapper.java` no tiene handler para JSONArray de objetos
- **Riesgo:** Koha devuelve `[{"type":"DNI","value":"12345678"}]` — el mapper haria `.toString()` del array entero

### P4: Paginacion imprecisa
- **Evidencia:** `PatronService.java:130` `moreResults = pageResults.length() == pageSize`
- **Riesgo:** Si la ultima pagina tiene exactamente pageSize items, se hace una request adicional innecesaria

---

## 5) Research

### Solucion 1: Patch mínimo — solo breaking changes
**Cambios:** PUT→PATCH, fix categories (read-only), fix paginacion
**Pros:** Riesgo minimo, rapido de implementar
**Contras:** No resuelve gaps funcionales para UPeU (no __ENABLE__, no extended_attrs, no campos nuevos)

### Solucion 2: Upgrade completo v1.2.0 (recomendada)
**Cambios:** Todo lo de solucion 1 + __ENABLE__, extended_attributes, campos Koha 25.11, logging, FilterTranslator mejorado, KohaResource.xml completo
**Pros:** Conector production-ready para UPeU. Resuelve todos los gaps identificados.
**Contras:** Mas cambios, mas riesgo. Requiere tests exhaustivos.

### Solucion 3: Rewrite a v2.0
**Cambios:** Reescritura completa con Java 17, SyncOp, schema discovery automatico
**Pros:** Conector de proxima generacion
**Contras:** Excesivo para las necesidades actuales. Delay significativo.

**Recommendation:** Solucion 2 (upgrade completo v1.2.0). Implementar en bloques ordenados por criticidad, con tests en cada bloque.

### Bloques de implementacion propuestos

#### Bloque A: Breaking changes Koha 25.11 (CRITICO)
1. **PUT → PATCH** para update de patron (`PatronService.updatePatron`)
   - Cambiar `HttpPut` a `HttpPatch` (Apache HttpClient tiene `HttpPatch`)
   - Agregar `import org.apache.http.client.methods.HttpPatch`
   - Eliminar el GET previo innecesario — PATCH acepta payload parcial
   - `processResponseErrors` ya maneja PATCH (verifica por metodo en 404)

2. **CategoryService: hacer read-only**
   - Eliminar `createCategory()`, `updateCategory()`, `deleteCategory()`
   - Actualizar `KohaConnector.create/update/delete` para lanzar `UnsupportedOperationException` en GROUP
   - Agregar `categorycode` como atributo al CategoryMapper (es el identificador alfanuerico real)

3. **Password endpoint**: si el conector maneja passwords en futuro, usar PATCH con password_2

4. **KohaResource.xml**: actualizar connectorVersion a `1.2.0`

#### Bloque B: Soporte __ENABLE__ para lifecycle (ALTA)
5. **Implementar OperationalAttributes.ENABLE_NAME**
   - Mapear `__ENABLE__ = true` → patron activo (expiry_date en futuro o null)
   - Mapear `__ENABLE__ = false` → patron desactivado (expiry_date = yesterday)
   - En read: `__ENABLE__ = !expired`
   - Alternativa: usar `patron_card_lost` como flag de desactivacion (mas explicito pero menos estandar)
   - **Decision del usuario necesaria**

6. **date_enrolled: hacer CREATABLE**
   - Quitar flag `NOT_CREATABLE` de `date_enrolled` en PatronMapper
   - Koha API acepta el campo en POST

#### Bloque C: extended_attributes (ALTA)
7. **Serializacion correcta de extended_attributes**
   - **Read (Koha→ConnId):** Convertir `[{"type":"DNI","value":"12345678"},{"type":"ORCID","value":"0000-0001-..."}]` a lista de strings `["DNI:12345678","ORCID:0000-0001-..."]`
   - **Write (ConnId→Koha):** Convertir `["DNI:12345678"]` a `[{"type":"DNI","value":"12345678"}]`
   - Usar `x-koha-embed: extended_attributes` header en GET patron para obtener attrs en una sola llamada
   - Implementar en PatronMapper con logica dedicada (no en BaseMapper generico)

#### Bloque D: Campos nuevos Koha 25.11 + UPeU (MEDIA)
8. **Agregar atributos nuevos al PatronMapper:**
   - `preferred_name` (String) — nuevo en 25.x
   - `pronouns` (String) — nuevo en 25.x
   - `primary_contact_method` (String) — nuevo en 25.x
   - `sms_number` (String) — necesario para UPeU SMS integration
   - `middle_name` (String)
   - `title` (String)
   - `other_name` (String)
   - `initials` (String)
   - `relationship_type` (String)
   - `sms_provider_id` (Integer)

9. **Agregar campos de direccion alternativa** (prefijo altaddress_):
   - `altaddress_address`, `altaddress_city`, `altaddress_state`, `altaddress_postal_code`, `altaddress_country`, `altaddress_email`, `altaddress_phone`

#### Bloque E: Mejoras de calidad (MEDIA)
10. **Logging controlado**
    - Mover logs de payload a nivel `LOG.info()` protegidos por guard: solo loguear si el nivel lo permite
    - Eliminar `payload.toString(2)` (pretty-print) en produccion — usar `payload.toString()` o truncar
    - Nunca loguear passwords ni tokens

11. **Paginacion con X-Total-Count**
    - Leer header `X-Total-Count` de la respuesta
    - Usar para determinar fin de paginas en lugar de comparar longitud
    - Fallback al metodo actual si el header no esta presente

12. **FilterTranslator: agregar ContainsFilter y StartsWithFilter**
    - ContainsFilter: usar `?field=value&_match=contains`
    - StartsWithFilter: usar `?field=value&_match=starts_with`
    - Agregar soporte para filtro por `category_id` y `library_id`

13. **KohaConfiguration.validate(): validar password en BASIC**
    - Verificar que `password != null` cuando `authenticationMethodStrategy = BASIC`

14. **Update de patron: eliminar GET previo**
    - PATCH acepta payload parcial — enviar solo los campos que cambiaron
    - Eliminar la logica de merge en KohaConnector.update()
    - Simplificar: construir JSON de cambios y enviar directo

#### Bloque F: KohaResource.xml completo para UPeU (BAJA)
15. **Reescribir KohaResource.xml con todos los outbound/inbound mappings**
    - connectorVersion: 1.2.0
    - Correlation por cardnumber (primario) y userid (secundario)
    - Outbound mappings para ~15 campos
    - Activation mapping basado en __ENABLE__ → expiry_date
    - Namespace correcto para extension schema UPeU

---

## 6) Clarification

Las siguientes decisiones necesitan input del usuario antes de proceder:

### C1: Estrategia de __ENABLE__ (activacion/desactivacion)
**Contexto:** MidPoint necesita habilitar/deshabilitar patrons en Koha para el lifecycle Leaver. Koha no tiene un campo nativo de "enabled/disabled" — es calculado de `expiry_date` y `debarred`.
**Opciones:**
- **A) expiry_date** — `__ENABLE__=false` → poner `expiry_date = yesterday`. Koha automaticamente marca `expired=true`. Reversible poniendo fecha futura.
- **B) patron_card_lost** — `__ENABLE__=false` → `patron_card_lost=true`. Bloquea acceso al OPAC. Mas explicito pero no es el uso semantico correcto.
- **C) Dual** — Usar `expiry_date` como mecanismo principal + `patron_card_lost` como flag adicional para bloqueo inmediato.
**Decision:** Opcion C (Dual). `patron_card_lost=true` bloquea inmediatamente. Si `patron_card_lost` es false o null, entonces `expiry_date` determina si esta activo. Logica en read: `__ENABLE__ = !patron_card_lost && !expired`. Logica en write: `__ENABLE__=false` → `patron_card_lost=true` + `expiry_date=yesterday`; `__ENABLE__=true` → `patron_card_lost=false` (expiry_date se gestiona por separado).

### C2: Verificacion PUT vs PATCH en Koha 25.11.03 desplegada
**Contexto:** El spec de Koha 25.11 define PATCH para updates, pero la version desplegada (25.11.03) podria aun aceptar PUT por retrocompatibilidad.
**Pregunta:** Quieres que verifique directamente contra la API de Koha en 192.168.12.135 si PUT sigue funcionando antes de cambiar a PATCH? O prefieres que vayamos directo a PATCH (lo correcto segun el spec)?
**Decision:** Ir directo a PATCH. Es el metodo correcto segun el spec de Koha 25.11 y futuro-proof.

### C3: Categories como read-only
**Contexto:** La API de Koha 25.11 solo expone `GET /patron_categories`. El conector actual tiene create/update/delete para categories.
**Decision:** Hacer categories read-only. Eliminar createCategory(), updateCategory(), deleteCategory() del CategoryService. Mantener getCategory() y searchCategories(). En KohaConnector, lanzar UnsupportedOperationException para Create/Update/Delete en ObjectClass.GROUP. Agregar `categorycode` como atributo (es el identificador real en Koha).

### C4: Formato de extended_attributes
**Contexto:** Los extended_attributes de Koha son pares `type:value`. Soportan multiples tipos de documento (DNI, carnet de extranjeria, pasaporte, ORCID, etc.).
**Decision:** Opcion B (JSON string). Cada elemento de la lista multivaluada es un JSON string: `'{"type":"DNI","value":"12345678"}'`. Esto permite manejar cualquier tipo de atributo extendido sin ambiguedad (valores que contengan `:` no rompen el parsing). Compatible con MidPoint que puede parsear JSON en scripts Groovy.

### C5: Scope del KohaResource.xml
**Contexto:** El XML actual es un placeholder minimo.
**Decision:** Generico (template). Solo mappings basicos sin scripts Groovy ni logica UPeU-especifica. Sirve como punto de partida para cualquier implementacion.

### C6: Manejo de DELETE 409 en patrons
**Contexto:** Koha devuelve 409 con codigos especificos si no puede borrar un patron: `has_checkouts`, `has_debt`, `has_guarantees`, `is_protected`.
**Decision:** Si, diferenciar. Mapear `has_checkouts` y `has_debt` a mensajes especificos en la excepcion ConnId para que MidPoint pueda tomar decisiones de policy. `is_protected` y `has_guarantees` tambien se diferencian.

### C7: Version de release
**Decision:** 1.2.0. Los categories nunca funcionaron realmente contra la API de Koha, asi que eliminar su CRUD no rompe nada en la practica.

### C8: Tests — alcance
**Decision:** Tests completos para todos los cambios (PATCH, __ENABLE__, extended_attributes, nuevos filtros, categories read-only, DELETE 409). Objetivo: alcanzar 80% de cobertura.
