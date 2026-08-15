package com.identicum.connectors;

import com.identicum.connectors.mappers.CategoryMapper;
import com.identicum.connectors.mappers.PatronMapper;
import com.identicum.connectors.model.AttributeMetadata;
import com.identicum.connectors.services.CategoryService;
import com.identicum.connectors.services.PatronService;
import com.identicum.connectors.services.HttpClientAdapter;
import com.identicum.connectors.services.DefaultHttpClientAdapter;
import com.identicum.connectors.services.JdbcConnectionProvider;
import com.identicum.connectors.services.PatronPermissionService;
import org.apache.http.impl.client.CloseableHttpClient;
import org.identityconnectors.common.logging.Log;
import org.identityconnectors.common.StringUtil;
import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.common.exceptions.AlreadyExistsException;
import org.identityconnectors.framework.common.exceptions.ConfigurationException;
import org.identityconnectors.framework.common.exceptions.ConnectorException;
import org.identityconnectors.framework.common.exceptions.ConnectorIOException;
import org.identityconnectors.framework.common.objects.*;
import org.identityconnectors.framework.common.objects.OperationalAttributeInfos;
import org.identityconnectors.framework.common.objects.filter.FilterTranslator;
import org.identityconnectors.framework.spi.Configuration;
import org.identityconnectors.framework.spi.Connector;
import org.identityconnectors.framework.spi.ConnectorClass;
import org.identityconnectors.framework.spi.PoolableConnector;
import org.identityconnectors.framework.spi.operations.*;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@ConnectorClass(displayNameKey = "connector.identicum.rest.display", configurationClass = KohaConfiguration.class)
public class KohaConnector implements PoolableConnector, CreateOp, UpdateOp, UpdateDeltaOp, SchemaOp, SearchOp<KohaFilter>, DeleteOp, TestOp {

	private static final Log LOG = Log.getLog(KohaConnector.class);

	private KohaConfiguration configuration;
	private CloseableHttpClient httpClient;
	private HttpClientAdapter httpAdapter;
	private PatronService patronService;
	private CategoryService categoryService;
	// Canal JDBC opcional para la autorizacion del patron (borrowers.flags y
	// user_permissions), que Koha NO expone por su API REST.
	// Nulos si dbEnabled=false o si la BD no estuvo disponible al init().
	private JdbcConnectionProvider jdbcConnectionProvider;
	private PatronPermissionService patronPermissionService;
	private final PatronMapper patronMapper = new PatronMapper();
	private final CategoryMapper categoryMapper = new CategoryMapper();
	private final AtomicReference<Schema> connectorSchema = new AtomicReference<>();

	@Override
	public KohaConfiguration getConfiguration() {
		return this.configuration;
	}

	@Override
	public void init(Configuration configuration) {
		this.configuration = (KohaConfiguration) configuration;
		this.configuration.validate(); // Validar la configuración al iniciar

		LOG.ok("Inicializando componentes del conector...");
		KohaAuthenticator authenticator = new KohaAuthenticator(getConfiguration());
		this.httpClient = authenticator.createAuthenticatedClient();
		this.httpAdapter = new DefaultHttpClientAdapter(this.httpClient);

		try {
			String serviceAddress = getConfiguration().getServiceAddress();
			this.patronService = new PatronService(this.httpAdapter, serviceAddress, this.configuration);
			this.categoryService = new CategoryService(this.httpAdapter, serviceAddress, this.configuration);
			LOG.ok("Conector Koha inicializado con éxito.");
		} catch (Exception e) {
			LOG.error(e, "Error durante la inicialización de los servicios del conector.");
			dispose(); // Limpiar recursos en caso de fallo
			throw new ConfigurationException("Fallo durante la inicialización de los servicios del conector: " + e.getMessage(), e);
		}

		// --- Inicializacion del canal JDBC opcional (autorizacion del patron) ---
		// Degradacion elegante: si la BD falla, se registra un warning y el
		// conector continua operativo solo en modo REST. La operacion test()
		// si fallara mas adelante (eso se decide en test(), no aqui).
		if (this.configuration.getDbEnabled()) {
			try {
				String dbPassword = extractGuardedString(this.configuration.getDbPassword());
				this.jdbcConnectionProvider = new JdbcConnectionProvider(
						this.configuration.getDbHost(),
						this.configuration.getDbPort(),
						this.configuration.getDbName(),
						this.configuration.getDbUser(),
						dbPassword,
						this.configuration.getDbPoolSize());
				this.patronPermissionService = new PatronPermissionService(this.jdbcConnectionProvider);
				LOG.ok("Canal JDBC para la autorizacion del patron inicializado.");
			} catch (Exception e) {
				// No abortar el init: REST sigue operativo.
				LOG.warn(e, "No se pudo inicializar el canal JDBC para la autorizacion del patron. "
						+ "El conector continua en modo REST unicamente. Detalle: {0}", e.getMessage());
				this.jdbcConnectionProvider = null;
				this.patronPermissionService = null;
			}
		} else {
			LOG.ok("Canal JDBC deshabilitado (dbEnabled=false). Conector en modo REST unicamente.");
		}
	}

	/**
	 * Extrae el valor en claro de un {@link GuardedString} usando el patron
	 * {@code accessor} de ConnId. El valor se copia a un {@code String} solo
	 * el tiempo imprescindible para configurar el DataSource.
	 */
	private String extractGuardedString(GuardedString guarded) {
		if (guarded == null) {
			return null;
		}
		final StringBuilder sb = new StringBuilder();
		guarded.access(chars -> sb.append(chars));
		return sb.toString();
	}

	@Override
	public void dispose() {
		LOG.ok("Liberando recursos del Conector Koha...");
		// dispose() puede invocarse DOS veces cuando init() falla: init() ya lo llama en su
		// propio catch y despues ConnId lo vuelve a llamar en su finally. Anular la
		// referencia evita cerrar el cliente HTTP dos veces (ruido en logs).
		try {
			if (httpAdapter != null) {
				httpAdapter.close();
				httpAdapter = null;
			}
		} catch (IOException e) {
			LOG.error("Error al cerrar el cliente HTTP: {0}", e.getMessage(), e);
			httpAdapter = null;
		}
		// Cerrar el pool JDBC si fue inicializado.
		if (jdbcConnectionProvider != null) {
			jdbcConnectionProvider.close();
			jdbcConnectionProvider = null;
			patronPermissionService = null;
		}
	}

	/**
	 * Implementa {@link PoolableConnector}: permite que ConnId reutilice esta instancia
	 * entre operaciones en lugar de invocar {@code init()}/{@code dispose()} en cada una.
	 *
	 * <p>Sin esto, MidPoint creaba y destruia un {@code KohaConnector} completo -incluyendo
	 * su pool JDBC propio- por CADA operacion de la API (cada create, update, get, search).
	 * En una reconciliacion de ~27.500 cuentas eso significaba decenas de miles de pools JDBC
	 * abriendose y cerrandose. Con {@code PoolableConnector}, ConnId mantiene hasta
	 * {@code connectorPoolConfiguration/maxObjects} instancias vivas y solo llama
	 * {@code checkAlive()} al pedir una del pool interno.</p>
	 *
	 * <p><strong>Debe ser barato</strong>: el framework lo invoca en cada
	 * {@code borrowObject()}, potencialmente una vez por operacion. Por eso NO hace ninguna
	 * llamada HTTP a Koha (eso si seria un round-trip de red por operacion). Se limita a:</p>
	 * <ul>
	 *   <li>Verificar que el cliente HTTP siga instanciado (validacion local, sin red).</li>
	 *   <li>Si el canal JDBC esta activo, validar el pool. El propio driver MariaDB limita
	 *       esa validacion a como maximo una vez por segundo por conexion
	 *       ({@code poolValidMinDelay}, default 1000ms), asi que llamadas frecuentes no
	 *       golpean la base de datos repetidamente.</li>
	 * </ul>
	 *
	 * <p>Si algo aqui lanza, ConnId descarta esta instancia (llama a {@code dispose()}) y
	 * crea una nueva en su lugar -comportamiento de autorecuperacion deseado.</p>
	 */
	@Override
	public void checkAlive() {
		if (httpAdapter == null) {
			throw new ConnectorException("KohaConnector.checkAlive(): httpAdapter no inicializado.");
		}
		if (jdbcConnectionProvider != null) {
			// Reutiliza testConnection(): toma una conexion del pool, valida con isValid(),
			// la devuelve. Con poolValidMinDelay del driver, no reconsulta la BD si ya se
			// valido hace menos de un segundo.
			jdbcConnectionProvider.testConnection();
		}
	}

	@Override
	public Schema schema() {
		Schema cached = this.connectorSchema.get();
		if (cached != null) return cached;
		LOG.ok("Construyendo esquema para el Conector Koha...");
		SchemaBuilder schemaBuilder = new SchemaBuilder(KohaConnector.class);

		// --- Esquema para Cuentas (Patrones) ---
		ObjectClassInfo accountInfo = buildObjectClassInfo(ObjectClass.ACCOUNT_NAME,
				PatronMapper.KOHA_PATRON_ID_NATIVE_NAME,
				PatronMapper.ATTRIBUTE_METADATA_MAP,
				"userid"); // "userid" is the ConnId Name for Patrons
		schemaBuilder.defineObjectClass(accountInfo);

		// --- Esquema para Grupos (Categorías) ---
		ObjectClassInfo groupInfo = buildObjectClassInfo(ObjectClass.GROUP_NAME,
				CategoryMapper.KOHA_CATEGORY_ID_NATIVE_NAME,
				CategoryMapper.ATTRIBUTE_METADATA_MAP,
				"name"); // "name" is the ConnId Name for Categories
		schemaBuilder.defineObjectClass(groupInfo);

		Schema built = schemaBuilder.build();
		this.connectorSchema.compareAndSet(null, built);
		LOG.ok("Esquema construido con éxito.");
		return this.connectorSchema.get();
	}

	@Override
	public Uid create(ObjectClass oClass, Set<Attribute> attrs, OperationOptions options) {
		LOG.ok("Iniciando Create para ObjectClass {0}, Atributos: {1}", oClass, attrs != null ? attrs.stream().map(Attribute::getName).collect(Collectors.toSet()) : "null");
		String newUidValue = null;
		try {
			if (ObjectClass.ACCOUNT.is(oClass.getObjectClassValue())) {
				JSONObject payload = patronMapper.buildPatronJson(attrs, true);
				// Apply __ENABLE__ if present
				Attribute enableAttr = AttributeUtil.find(OperationalAttributes.ENABLE_NAME, attrs);
				if (enableAttr != null) {
					Boolean enabled = AttributeUtil.getBooleanValue(enableAttr);
					patronMapper.applyEnableAttribute(payload, enabled);
				}
				JSONObject response = patronService.createPatron(payload);
				Object patronIdObj = response.opt(PatronMapper.KOHA_PATRON_ID_NATIVE_NAME);
				if (patronIdObj == null || patronIdObj == JSONObject.NULL) {
					throw new ConnectorException("Koha CREATE patron devolvió respuesta sin " + PatronMapper.KOHA_PATRON_ID_NATIVE_NAME + ". Response: " + response);
				}
				newUidValue = String.valueOf(patronIdObj);
				if ("null".equals(newUidValue) || newUidValue.trim().isEmpty()) {
					throw new ConnectorException("Koha CREATE patron devolvió " + PatronMapper.KOHA_PATRON_ID_NATIVE_NAME + " nulo o vacío. Response: " + response);
				}
				// Los permisos son autorizacion: si fueron solicitados deben persistirse
				// o la operacion falla; nunca se informa un falso exito a MidPoint.
				applyAuthorizationFromAttributes(newUidValue, attrs);
			} else if (ObjectClass.GROUP.is(oClass.getObjectClassValue())) {
				throw new UnsupportedOperationException("Patron categories are read-only in Koha API");
			} else {
				throw new UnsupportedOperationException("Operación Create no soportada para: " + oClass.getObjectClassValue());
			}
			LOG.ok("Create para ObjectClass {0} completado. Uid: {1}", oClass, newUidValue);
			return new Uid(newUidValue);
		} catch (AlreadyExistsException e) {
			// La idempotencia del 409 se resuelve 100% por REST en PatronService.createPatron
			// (colisiones de campos unicos: cardnumber -> userid -> email). Con arranque de cero
			// y cardnumber=ID_PERSONA (unico e inmutable) la correlacion limpia por cardnumber
			// es suficiente; ya no hay adopt-by-DNI legacy (D-14).
			LOG.error(e, "Error AlreadyExistsException irrecuperable en Create para ObjectClass {0}", oClass.getObjectClassValue());
			throw e;
		} catch (ConnectorException e) { // Catch specific ConnectorExceptions first
			LOG.error(e, "Error de ConnectorException en Create para ObjectClass {0}", oClass.getObjectClassValue());
			throw e; // Re-throw original ConnectorException
		} catch (IOException e) {
			LOG.error(e, "Error de IOException en Create para ObjectClass {0}", oClass.getObjectClassValue());
			throw new ConnectorIOException("Error de IO en Create para " + oClass.getObjectClassValue() + ": " + e.getMessage(), e);
		} catch (Exception e) {
			LOG.error(e, "Error inesperado en Create para ObjectClass {0}", oClass.getObjectClassValue());
			throw ConnectorException.wrap(e);
		}
	}

	/**
	 * Aplica un update expresado como DELTAS (ConnId {@link AttributeDelta}).
	 *
	 * <p>MOTIVO (2026-08-15). Hasta ahora el conector solo implementaba {@link UpdateOp},
	 * que no sabe de altas y bajas: recibe el valor final de cada atributo. Cuando MidPoint
	 * calcula un cambio de valor sobre un atributo MULTIVALOR —para
	 * {@code extended_attributes} produce dos itemDelta, {@code ADD {"type":"STUDY_LEVEL",
	 * "value":"pregrado"}} y {@code DELETE {"type":"STUDY_LEVEL","value":"posgrado"}}—,
	 * el framework se los entrega a un UpdateOp <b>fusionados en un unico atributo</b>.
	 * El conector veia entonces DOS entradas de STUDY_LEVEL y las enviaba las dos a Koha,
	 * que responde
	 * {@code 409 "Tried to add more than one non-repeatable attributes"} y aborta la
	 * operacion completa del patron.</p>
	 *
	 * <p>Medido en PROD el 15-ago-2026 con el log del conector en TRACE:
	 * {@code PUT extended_attributes patron 4067: 7 entradas} cuando el patron solo tiene
	 * 6 atributos (1 STUDY_LEVEL + 1 COD_UPEU + 1 DNI + 3 STUDYCYCLE). El delta de MidPoint
	 * era correcto; lo que faltaba era una interfaz capaz de expresarlo.</p>
	 *
	 * <p>Al implementar {@link UpdateDeltaOp}, ConnId entrega las altas y las bajas por
	 * separado y aqui se resuelven contra el estado real del patron antes de escribir:
	 * {@code final = actuales - valuesToRemove + valuesToAdd}. El resto de atributos se
	 * traduce a su valor final y se delega en {@link #update} sin cambios.</p>
	 */
	@Override
	public Set<AttributeDelta> updateDelta(ObjectClass oClass, Uid uid, Set<AttributeDelta> deltas, OperationOptions options) {
		LOG.ok("Iniciando UpdateDelta para ObjectClass {0}, Uid: {1}, Deltas: {2}", oClass, uid.getUidValue(),
				deltas != null ? deltas.stream().map(AttributeDelta::getName).collect(Collectors.toSet()) : "null");
		if (deltas == null || deltas.isEmpty()) {
			return java.util.Collections.emptySet();
		}
		Set<Attribute> attrs = new java.util.HashSet<>();
		for (AttributeDelta d : deltas) {
			String name = d.getName();
			if (d.getValuesToReplace() != null) {
				// Atributo single-value o reemplazo explicito: se pasa tal cual.
				attrs.add(AttributeBuilder.build(name, d.getValuesToReplace()));
			} else {
				// Multivalor por altas/bajas: se resuelve contra el estado ACTUAL del recurso,
				// que es la unica forma de no perder los valores que MidPoint no menciona.
				attrs.add(AttributeBuilder.build(name, resolveMultivalued(oClass, uid, name, d)));
			}
		}
		update(oClass, uid, attrs, options);
		return java.util.Collections.emptySet();
	}

	/**
	 * Calcula el valor final de un atributo multivaluado: actuales - bajas + altas.
	 * Lee los actuales del propio recurso (no del shadow de MidPoint) para que el
	 * resultado refleje lo que Koha tiene de verdad.
	 */
	private java.util.List<Object> resolveMultivalued(ObjectClass oClass, Uid uid, String name, AttributeDelta delta) {
		java.util.List<Object> valores = new java.util.ArrayList<>();
		if ("extended_attributes".equals(name)) {
			try {
				JSONObject patron = patronService.getPatron(uid.getUidValue());
				Object raw = patron != null ? patron.opt("extended_attributes") : null;
				if (raw instanceof JSONArray) {
					JSONArray arr = (JSONArray) raw;
					for (int i = 0; i < arr.length(); i++) {
						JSONObject e = arr.optJSONObject(i);
						if (e == null) continue;
						String t = e.optString("type", null);
						if (t == null || t.isEmpty()) continue;
						// Misma forma canonica {"type":X,"value":Y} que usa MidPoint en sus deltas,
						// para que removeAll() case por igualdad de String.
						JSONObject norm = new JSONObject();
						norm.put("type", t);
						norm.put("value", e.optString("value", ""));
						valores.add(norm.toString());
					}
				}
			} catch (Exception e) {
				// Sin lectura previa se aplican solo las altas: es preferible a abortar el update
				// entero, y el merge-preserve de PatronService conserva los tipos no gobernados.
				LOG.warn(e, "UpdateDelta {0}: no se pudo leer el valor actual de {1}; se aplican solo las altas",
						uid.getUidValue(), name);
			}
		}
		if (delta.getValuesToRemove() != null) {
			valores.removeAll(delta.getValuesToRemove());
		}
		if (delta.getValuesToAdd() != null) {
			for (Object v : delta.getValuesToAdd()) {
				if (!valores.contains(v)) {
					valores.add(v);
				}
			}
		}
		return valores;
	}

	@Override
	public Uid update(ObjectClass oClass, Uid uid, Set<Attribute> attrs, OperationOptions options) {
		LOG.ok("Iniciando Update para ObjectClass {0}, Uid: {1}, Atributos: {2}", oClass, uid.getUidValue(), attrs != null ? attrs.stream().map(Attribute::getName).collect(Collectors.toSet()) : "null");
		if (attrs == null || attrs.isEmpty()) {
			LOG.ok("Update para ObjectClass {0}, Uid: {1} no requiere cambios (atributos vacíos).", oClass, uid.getUidValue());
			return uid;
		}
		try {
			if (ObjectClass.ACCOUNT.is(oClass.getObjectClassValue())) {
				JSONObject changes = patronMapper.buildPatronJson(attrs, false);
				Attribute enableAttr = AttributeUtil.find(OperationalAttributes.ENABLE_NAME, attrs);
				if (enableAttr != null) {
					Boolean enabled = AttributeUtil.getBooleanValue(enableAttr);
					patronMapper.applyEnableAttribute(changes, enabled);
				}
				// buildPatronJson excluye del payload REST los canales que no viajan por REST
				// (flags/user_permissions por JDBC, extended_attributes por endpoint dedicado).
				// El PUT principal solo se envia si quedan cambios reales que aplicar.
				if (changes.length() > 0) {
					patronService.updatePatron(uid.getUidValue(), changes);
				} else {
					LOG.ok("Update Uid {0}: sin cambios en el PUT principal (solo extended_attributes/autorizacion).", uid.getUidValue());
				}

				// v1.3.11 — extended_attributes en UPDATE: la API Koha NO los acepta en el
				// PUT /patrons/{id}; se escriben por el endpoint dedicado
				// PUT /patrons/{id}/extended_attributes (overwrite-all con merge-preserve).
				// Esto habilita el backfill de STUDYCYCLE (y demas) a borrowers EXISTENTES
				// via reconcile de MidPoint. Si el atributo no vino en el delta -> NO-OP.
				JSONArray extAttrs = patronMapper.extractExtendedAttributesArray(attrs);
				if (extAttrs != null) {
					LOG.ok("Update Uid {0}: escribiendo extended_attributes ({1} entradas) via endpoint dedicado.",
							uid.getUidValue(), extAttrs.length());
					patronService.replaceExtendedAttributes(uid.getUidValue(), extAttrs);
				}
				applyAuthorizationFromAttributes(uid.getUidValue(), attrs);
			} else if (ObjectClass.GROUP.is(oClass.getObjectClassValue())) {
				throw new UnsupportedOperationException("Patron categories are read-only in Koha API");
			} else {
				throw new UnsupportedOperationException("Operación Update no soportada para: " + oClass.getObjectClassValue());
			}
			LOG.ok("Update para ObjectClass {0}, Uid: {1} completado.", oClass, uid.getUidValue());
			return uid;
		} catch (ConnectorException e) { // Catch specific ConnectorExceptions first
			LOG.error(e, "Error de ConnectorException en Update para ObjectClass {0}, Uid {1}", oClass.getObjectClassValue(), uid.getUidValue());
			throw e; // Re-throw original ConnectorException
		} catch (IOException e) {
			LOG.error(e, "Error de IOException en Update para ObjectClass {0}, Uid {1}", oClass.getObjectClassValue(), uid.getUidValue());
			throw new ConnectorIOException("Error de IO en Update para " + oClass.getObjectClassValue() + ", Uid: " + uid.getUidValue() + ": " + e.getMessage(), e);
		} catch (Exception e) {
			LOG.error(e, "Error inesperado en Update para ObjectClass {0}, Uid {1}", oClass.getObjectClassValue(), uid.getUidValue());
			throw ConnectorException.wrap(e);
		}
	}

	@Override
	public void delete(ObjectClass oClass, Uid uid, OperationOptions options) {
		LOG.ok("Iniciando Delete para ObjectClass {0}, Uid: {1}", oClass, uid.getUidValue());
		try {
			if (ObjectClass.ACCOUNT.is(oClass.getObjectClassValue())) {
				patronService.deletePatron(uid.getUidValue());
			} else if (ObjectClass.GROUP.is(oClass.getObjectClassValue())) {
				throw new UnsupportedOperationException("Patron categories are read-only in Koha API");
			} else {
				throw new UnsupportedOperationException("Operación Delete no soportada para: " + oClass.getObjectClassValue());
			}
			LOG.ok("Delete para ObjectClass {0}, Uid: {1} completado.", oClass, uid.getUidValue());
		} catch (ConnectorException e) { // Catch specific ConnectorExceptions first
			LOG.error(e, "Error de ConnectorException en Delete para ObjectClass {0}, Uid {1}", oClass.getObjectClassValue(), uid.getUidValue());
			throw e; // Re-throw original ConnectorException
		} catch (IOException e) {
			LOG.error(e, "Error de IOException en Delete para ObjectClass {0}, Uid {1}", oClass.getObjectClassValue(), uid.getUidValue());
			throw new ConnectorIOException("Error de IO en Delete para " + oClass.getObjectClassValue() + ", Uid: " + uid.getUidValue() + ": " + e.getMessage(), e);
		} catch (Exception e) {
			LOG.error(e, "Error inesperado en Delete para ObjectClass {0}, Uid {1}", oClass.getObjectClassValue(), uid.getUidValue());
			throw ConnectorException.wrap(e);
		}
	}

	@Override
	public void executeQuery(ObjectClass oClass, KohaFilter filter, ResultsHandler handler, OperationOptions options) {
		LOG.ok("Iniciando executeQuery para ObjectClass {0}. Filtro Uid: {1}, Filtro Name: {2}, Filtro Email: {3}, Filtro Cardnumber: {4}, Options: {5}",
				oClass,
				(filter != null ? filter.getByUid() : "N/A"),
				(filter != null ? filter.getByName() : "N/A"),
				(filter != null ? filter.getByEmail() : "N/A"),
				(filter != null ? filter.getByCardNumber() : "N/A"),
				options);
		try {
			if (ObjectClass.ACCOUNT.is(oClass.getObjectClassValue())) {
				// La autorizacion solo se lee si MidPoint la pide EXPLICITAMENTE via
				// attributesToGet. Nunca una consulta JDBC por fila en busquedas masivas.
				boolean fetchFlags = isFlagsRequested(options);
				boolean fetchPermissions = isPermissionsRequested(options);
				if (filter != null && filter.getByUid() != null) {
					JSONObject patronJson = patronService.getPatron(filter.getByUid());
					if (patronJson != null && patronJson.length() > 0) { // Check if patronJson is not null or empty
						ConnectorObject co = patronMapper.convertJsonToPatronObject(patronJson);
						co = enrichWithAuthorization(co, fetchFlags, fetchPermissions);
						if (co != null) handler.handle(co);
						LOG.info("Resultados de búsqueda por UID para {0}: 1", oClass);
					} else {
						LOG.info("Resultados de búsqueda por UID para {0}: 0 (Patrón no encontrado o vacío)", oClass);
					}
				} else {
					patronService.searchPatrons(filter, options, patronJson -> {
						ConnectorObject co = patronMapper.convertJsonToPatronObject(patronJson);
						co = enrichWithAuthorization(co, fetchFlags, fetchPermissions);
						return co == null || handler.handle(co);
					});
				}
			} else if (ObjectClass.GROUP.is(oClass.getObjectClassValue())) {
				if (filter != null && filter.getByUid() != null) {
					JSONObject categoryJson = categoryService.getCategory(filter.getByUid());
					if (categoryJson != null && categoryJson.length() > 0) { // Check if categoryJson is not null or empty
						handler.handle(categoryMapper.convertJsonToCategoryObject(categoryJson));
						LOG.info("Resultados de búsqueda por UID para {0}: 1", oClass);
					} else {
						LOG.info("Resultados de búsqueda por UID para {0}: 0 (Categoría no encontrada o vacía)", oClass);
					}
				} else {
					JSONArray results = categoryService.searchCategories(filter, options);
					LOG.info("Resultados de búsqueda para {0}: {1}", oClass, results != null ? results.length() : 0);
					if (results != null) {
						for (int i = 0; i < results.length(); i++) {
							ConnectorObject co = categoryMapper.convertJsonToCategoryObject(results.getJSONObject(i));
							if (co != null && !handler.handle(co)) break;
						}
					}
				}
			}
			LOG.ok("executeQuery para ObjectClass {0} completado.", oClass);
		} catch (ConnectorException e) { // Catch specific ConnectorExceptions first
			LOG.error(e, "Error de ConnectorException en executeQuery para ObjectClass {0}", oClass.getObjectClassValue());
			throw e; // Re-throw original ConnectorException
		} catch (IOException e) {
			LOG.error(e, "Error de IOException en executeQuery para ObjectClass {0}", oClass.getObjectClassValue());
			throw new ConnectorIOException("Error de IO en executeQuery para " + oClass.getObjectClassValue() + ": " + e.getMessage(), e);
		} catch (Exception e) {
			LOG.error(e, "Error inesperado en executeQuery para ObjectClass {0}", oClass.getObjectClassValue());
			throw ConnectorException.wrap(e);
		}
	}

	@Override
	public void test() {
		LOG.ok("Iniciando prueba de conexión...");
		try {
			// Paso 1: Probar la obtención y validación del esquema
			LOG.ok("Paso 1/2: Probando la obtención del esquema...");
			Schema schema = schema(); // Llama al método schema() de esta clase
			if (schema == null) {
				throw new ConnectorIOException("La obtención del esquema retornó null.");
			}
			// Validar que el esquema contiene las ObjectClass esperadas
			if (schema.findObjectClassInfo(ObjectClass.ACCOUNT_NAME) == null) {
				throw new ConnectorIOException("El esquema no contiene la ObjectClass ACCOUNT.");
			}
			if (schema.findObjectClassInfo(ObjectClass.GROUP_NAME) == null) {
				throw new ConnectorIOException("El esquema no contiene la ObjectClass GROUP.");
			}
			LOG.ok("Paso 1/2: Obtención y validación básica del esquema exitosa.");

			// Paso 2: Probar conectividad REST (single GET, sin paginación)
			LOG.ok("Paso 2/3: Probando conectividad REST...");
			patronService.testConnection();

			// Paso 3: Probar el canal JDBC SI fue configurado.
			// La degradacion elegante aplica solo a CRUD, NO a Test Connection:
			// si el operador habilito JDBC, debe saber si funciona.
			if (this.configuration.getDbEnabled()) {
				LOG.ok("Paso 3/3: Probando el canal JDBC (autorizacion del patron)...");
				if (this.patronPermissionService == null) {
					throw new ConnectorIOException("El canal JDBC esta habilitado (dbEnabled=true) "
							+ "pero no se pudo inicializar durante init(). Revise host, credenciales "
							+ "y conectividad con la base de datos de Koha.");
				}
				this.patronPermissionService.testConnection();
				LOG.ok("Paso 3/3: Canal JDBC operativo.");
			} else {
				LOG.ok("Paso 3/3: Canal JDBC deshabilitado, se omite la prueba.");
			}

			LOG.ok("Prueba de conexión y configuración básica completada con éxito.");

		} catch (ConnectorException e) { // Catch ConnectorException specifically (includes ConnectorIOException)
			LOG.error(e, "La prueba del conector falló con ConnectorException: {0}", e.getMessage());
			throw e; // Re-throw as is
		} catch (IOException e) { // Catch IOException if not wrapped by services or schema()
			LOG.error(e, "La prueba del conector falló con IOException: {0}", e.getMessage());
			throw new ConnectorIOException("La prueba de conexión falló por IO: " + e.getMessage(), e);
		} catch (Exception e) { // Catch any other unexpected exception
			LOG.error(e, "La prueba del conector falló con una excepción inesperada: {0}", e.getMessage());
			// Wrap in ConnectorIOException as it's the most common type for test() failures.
			throw new ConnectorIOException("La prueba de conexión falló inesperadamente: " + e.getMessage(), e);
		}
	}

	@Override
	public FilterTranslator<KohaFilter> createFilterTranslator(ObjectClass oClass, OperationOptions options) {
		return new KohaFilterTranslator();
	}

	// --- Métodos de ayuda ---

	private ObjectClassInfo buildObjectClassInfo(String objectClassType,
												 String nativeIdAttributeName,
												 java.util.Map<String, AttributeMetadata> attributeMetadataMap,
												 String connIdNameAttribute) {
		ObjectClassInfoBuilder ociBuilder = new ObjectClassInfoBuilder();
		ociBuilder.setType(objectClassType);

		// UID attribute
		ociBuilder.addAttributeInfo(AttributeInfoBuilder.define(Uid.NAME)
				.setNativeName(nativeIdAttributeName).setType(String.class)
				.setRequired(true).setCreateable(false).setUpdateable(false).setReadable(true).build());

		// Name attribute
		AttributeMetadata nameMeta = attributeMetadataMap.get(connIdNameAttribute);
		if (nameMeta == null) {
			// Fallback or error if the primary name attribute is not in the map
			// For now, let's assume it's always present as per current logic
			LOG.warn("Primary name attribute '{0}' not found in metadata map for ObjectClass '{1}'. Schema might be incomplete.", connIdNameAttribute, objectClassType);
		} else {
			ociBuilder.addAttributeInfo(AttributeInfoBuilder.define(Name.NAME)
					.setNativeName(nameMeta.getKohaNativeName()).setType(String.class)
					.setRequired(nameMeta.isRequired()).build());
		}

		// Other attributes
		for (AttributeMetadata meta : attributeMetadataMap.values()) {
			if (!connIdNameAttribute.equals(meta.getConnIdName())) { // Exclude the one already added as Name.NAME
				ociBuilder.addAttributeInfo(createAttributeInfo(meta)); // Uses existing helper
			}
		}
		// Add __ENABLE__ operational attribute for ACCOUNT type
		if (ObjectClass.ACCOUNT_NAME.equals(objectClassType)) {
			ociBuilder.addAttributeInfo(OperationalAttributeInfos.ENABLE);
		}
		return ociBuilder.build();
	}

	private AttributeInfo createAttributeInfo(AttributeMetadata meta) {
		AttributeInfoBuilder builder = new AttributeInfoBuilder(meta.getConnIdName());
		builder.setNativeName(meta.getKohaNativeName());
		builder.setType(meta.getType());
		builder.setRequired(meta.isRequired());
		builder.setMultiValued(meta.isMultivalued());
		builder.setCreateable(!meta.isNotCreatable());
		builder.setUpdateable(!meta.isNotUpdateable());
		builder.setReadable(!meta.isNotReadable());
		if (meta.isNotReturnedByDefault()) {
			builder.setReturnedByDefault(false);
		}
		return builder.build();
	}


	private boolean isFlagsRequested(OperationOptions options) {
		if (options == null || options.getAttributesToGet() == null) {
			return true;
		}
		for (String attr : options.getAttributesToGet()) {
			if (PatronMapper.ATTR_FLAGS.equals(attr)) {
				return true;
			}
		}
		return false;
	}

	private boolean isPermissionsRequested(OperationOptions options) {
		if (options == null || options.getAttributesToGet() == null) {
			return true;
		}
		for (String attr : options.getAttributesToGet()) {
			if (PatronMapper.ATTR_USER_PERMISSIONS.equals(attr)) {
				return true;
			}
		}
		return false;
	}

	private void applyAuthorizationFromAttributes(String borrowernumber, Set<Attribute> attrs) {
		if (attrs == null) {
			return;
		}
		Attribute flagsAttr = AttributeUtil.find(PatronMapper.ATTR_FLAGS, attrs);
		Attribute permissionsAttr = AttributeUtil.find(PatronMapper.ATTR_USER_PERMISSIONS, attrs);
		if (flagsAttr == null && permissionsAttr == null) {
			return;
		}
		if (patronPermissionService == null) {
			throw new ConnectorIOException("Se solicito provisionar borrowers.flags para el patron "
					+ borrowernumber + " pero el canal JDBC no esta operativo.");
		}
		Integer flags = null;
		if (flagsAttr != null) {
			java.util.List<Object> values = flagsAttr.getValue();
			if (values != null && !values.isEmpty() && values.get(0) != null) {
				Object value = values.get(0);
				if (value instanceof Number) {
					flags = ((Number) value).intValue();
				} else {
					try {
						flags = Integer.valueOf(String.valueOf(value));
					} catch (NumberFormatException e) {
						throw new org.identityconnectors.framework.common.exceptions.InvalidAttributeValueException(
								"El atributo 'flags' debe ser Integer. Valor: " + value, e);
					}
				}
			}
		}
		if (permissionsAttr == null) {
			patronPermissionService.updateFlags(borrowernumber, flags);
			return;
		}
		java.util.Set<String> permissions = new java.util.LinkedHashSet<>();
		java.util.List<Object> permissionValues = permissionsAttr.getValue();
		if (permissionValues != null) {
			for (Object value : permissionValues) {
				if (value != null) permissions.add(String.valueOf(value));
			}
		}
		if (flagsAttr == null) {
			flags = patronPermissionService.getFlags(borrowernumber);
		}
		patronPermissionService.replaceAuthorization(borrowernumber, flags, permissions);
	}


	private ConnectorObject enrichWithAuthorization(ConnectorObject co, boolean fetchFlags, boolean fetchPermissions) {
		if (co == null || (!fetchFlags && !fetchPermissions)) {
			return co;
		}
		if (patronPermissionService == null) {
			throw new ConnectorIOException("MidPoint solicito borrowers.flags pero el canal JDBC no esta operativo.");
		}
		ConnectorObjectBuilder builder = new ConnectorObjectBuilder();
		builder.setObjectClass(co.getObjectClass());
		builder.addAttributes(co.getAttributes());
		if (fetchFlags) {
			Integer flags = patronPermissionService.getFlags(co.getUid().getUidValue());
			if (flags != null) builder.addAttribute(AttributeBuilder.build(PatronMapper.ATTR_FLAGS, flags));
		}
		if (fetchPermissions) {
			java.util.Set<String> permissions = patronPermissionService.getPermissions(co.getUid().getUidValue());
			builder.addAttribute(AttributeBuilder.build(PatronMapper.ATTR_USER_PERMISSIONS,
					permissions.toArray(new String[0])));
		}
		return builder.build();
	}
}
