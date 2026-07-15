package com.identicum.connectors;

import com.identicum.connectors.mappers.CategoryMapper;
import com.identicum.connectors.mappers.PatronMapper;
import com.identicum.connectors.model.AttributeMetadata;
import com.identicum.connectors.services.CategoryService;
import com.identicum.connectors.services.PatronService;
import com.identicum.connectors.services.HttpClientAdapter;
import com.identicum.connectors.services.DefaultHttpClientAdapter;
import com.identicum.connectors.services.JdbcConnectionProvider;
import com.identicum.connectors.services.PatronImageService;
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
import org.identityconnectors.framework.spi.operations.*;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@ConnectorClass(displayNameKey = "connector.identicum.rest.display", configurationClass = KohaConfiguration.class)
public class KohaConnector implements Connector, CreateOp, UpdateOp, SchemaOp, SearchOp<KohaFilter>, DeleteOp, TestOp {

	private static final Log LOG = Log.getLog(KohaConnector.class);

	private KohaConfiguration configuration;
	private CloseableHttpClient httpClient;
	private HttpClientAdapter httpAdapter;
	private PatronService patronService;
	private CategoryService categoryService;
	// Canal JDBC opcional para la foto del patron (tabla patronimage).
	// Nulos si dbEnabled=false o si la BD no estuvo disponible al init().
	private JdbcConnectionProvider jdbcConnectionProvider;
	private PatronImageService patronImageService;
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

		// --- Inicializacion del canal JDBC opcional (foto del patron) ---
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
				this.patronImageService = new PatronImageService(this.jdbcConnectionProvider);
				LOG.ok("Canal JDBC para foto de patron inicializado.");
			} catch (Exception e) {
				// No abortar el init: REST sigue operativo.
				LOG.warn(e, "No se pudo inicializar el canal JDBC para la foto del patron. "
						+ "El conector continua en modo REST unicamente. Detalle: {0}", e.getMessage());
				this.jdbcConnectionProvider = null;
				this.patronImageService = null;
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
		try {
			if (httpAdapter != null) {
				httpAdapter.close();
			}
		} catch (IOException e) {
			LOG.error("Error al cerrar el cliente HTTP: {0}", e.getMessage(), e);
		}
		// Cerrar el pool JDBC si fue inicializado.
		if (jdbcConnectionProvider != null) {
			jdbcConnectionProvider.close();
			jdbcConnectionProvider = null;
			patronImageService = null;
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
				// La foto se escribe DESPUES del POST REST (best-effort: no abortar si falla).
				try {
					applyPhotoFromAttributes(newUidValue, attrs);
				} catch (Exception jdbcEx) {
					LOG.warn("JDBC photo write falló en CREATE para patron {0} (REST ya fue exitoso). "
							+ "La foto NO se guardó pero el patron SÍ fue creado. Error: {1}",
							newUidValue, jdbcEx.getMessage());
				}
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
				// buildPatronJson excluye photo/photo_mimetype del payload REST.
				// Tambien excluye extended_attributes del PUT principal (van por endpoint dedicado).
				// El PUT principal solo se envia si quedan cambios reales que aplicar.
				if (changes.length() > 0) {
					patronService.updatePatron(uid.getUidValue(), changes);
				} else {
					LOG.ok("Update Uid {0}: sin cambios en el PUT principal (solo foto/extended_attributes).", uid.getUidValue());
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
				// La foto se procesa por el canal JDBC (best-effort: no abortar si falla).
				try {
					applyPhotoFromAttributes(uid.getUidValue(), attrs);
				} catch (Exception jdbcEx) {
					LOG.warn("JDBC photo update falló para patron {0} (operacion REST ya fue exitosa). "
							+ "La foto NO se actualizó pero el resto del update sí. Error: {1}",
							uid.getUidValue(), jdbcEx.getMessage());
				}
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
				// La foto solo se lee si MidPoint la pide EXPLICITAMENTE via
				// attributesToGet. Nunca un SELECT de blob por fila en busquedas masivas.
				boolean fetchPhoto = isPhotoRequested(options);
				if (filter != null && filter.getByUid() != null) {
					JSONObject patronJson = patronService.getPatron(filter.getByUid());
					if (patronJson != null && patronJson.length() > 0) { // Check if patronJson is not null or empty
						ConnectorObject co = patronMapper.convertJsonToPatronObject(patronJson);
						co = enrichWithPhoto(co, fetchPhoto);
						if (co != null) handler.handle(co);
						LOG.info("Resultados de búsqueda por UID para {0}: 1", oClass);
					} else {
						LOG.info("Resultados de búsqueda por UID para {0}: 0 (Patrón no encontrado o vacío)", oClass);
					}
				} else {
					patronService.searchPatrons(filter, options, patronJson -> {
						ConnectorObject co = patronMapper.convertJsonToPatronObject(patronJson);
						co = enrichWithPhoto(co, fetchPhoto);
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
				LOG.ok("Paso 3/3: Probando el canal JDBC (foto de patron)...");
				if (this.patronImageService == null) {
					throw new ConnectorIOException("El canal JDBC esta habilitado (dbEnabled=true) "
							+ "pero no se pudo inicializar durante init(). Revise host, credenciales "
							+ "y conectividad con la base de datos de Koha.");
				}
				this.patronImageService.testConnection();
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

	// --- Helpers del canal JDBC (foto del patron) ---

	/**
	 * Procesa los atributos de foto ({@code photo} / {@code photo_mimetype})
	 * para una operacion de create o update sobre un patron.
	 *
	 * <p>Reglas:</p>
	 * <ul>
	 *   <li>Si el canal JDBC no esta operativo, no hace nada (degradacion elegante del CRUD).</li>
	 *   <li>Si el atributo {@code photo} NO viene en el conjunto de atributos: NO-OP.</li>
	 *   <li>Si {@code photo} viene con valor: upsert idempotente a la tabla {@code patronimage}.</li>
	 *   <li>Si {@code photo} viene con valor null o vacio: <strong>NO-OP</strong>.
	 *       JAMAS se borra {@code patronimage} cuando la foto llega null; la ausencia
	 *       de valor se interpreta como "sin cambios", no como "borrar".</li>
	 * </ul>
	 *
	 * @param borrowernumber clave del patron (= patron_id = __UID__)
	 * @param attrs          atributos de la operacion
	 */
	private void applyPhotoFromAttributes(String borrowernumber, Set<Attribute> attrs) {
		if (attrs == null) {
			return;
		}
		Attribute photoAttr = AttributeUtil.find(PatronMapper.ATTR_PHOTO, attrs);
		if (photoAttr == null) {
			// El atributo no fue enviado: NO-OP.
			return;
		}
		if (patronImageService == null) {
			// JDBC no operativo: degradacion elegante del CRUD.
			LOG.warn("Se recibio el atributo '{0}' para el patron {1} pero el canal JDBC "
					+ "no esta operativo. La foto NO se provisiono.",
					PatronMapper.ATTR_PHOTO, borrowernumber);
			return;
		}

		byte[] photoBytes = extractPhotoBytes(photoAttr);
		if (photoBytes == null || photoBytes.length == 0) {
			// photo presente pero null/vacio: NO-OP. NUNCA borrar aqui.
			LOG.ok("Atributo '{0}' presente con valor null/vacio para el patron {1}: NO-OP "
					+ "(la foto existente no se modifica ni se borra).",
					PatronMapper.ATTR_PHOTO, borrowernumber);
			return;
		}

		Attribute mimeAttr = AttributeUtil.find(PatronMapper.ATTR_PHOTO_MIMETYPE, attrs);
		String mimetype = (mimeAttr != null) ? AttributeUtil.getStringValue(mimeAttr) : null;
		if (StringUtil.isBlank(mimetype)) {
			// Inferir un default razonable; PatronImageService validara que sea permitido.
			mimetype = "image/jpeg";
			LOG.ok("No se especifico '{0}' para el patron {1}; se asume {2}.",
					PatronMapper.ATTR_PHOTO_MIMETYPE, borrowernumber, mimetype);
		}

		LOG.ok("Provisionando foto del patron {0} via JDBC ({1} bytes, mimetype={2}).",
				borrowernumber, photoBytes.length, mimetype);
		patronImageService.upsertImage(borrowernumber, photoBytes, mimetype);
	}

	/**
	 * Extrae los bytes de la foto de un atributo ConnId. Soporta valores de
	 * tipo {@code byte[]}.
	 *
	 * @param photoAttr atributo de foto (no nulo)
	 * @return los bytes, o {@code null} si el atributo no tiene valor
	 */
	private byte[] extractPhotoBytes(Attribute photoAttr) {
		java.util.List<Object> values = photoAttr.getValue();
		if (values == null || values.isEmpty() || values.get(0) == null) {
			return null;
		}
		Object value = values.get(0);
		if (value instanceof byte[]) {
			return (byte[]) value;
		}
		throw new org.identityconnectors.framework.common.exceptions.InvalidAttributeValueException(
				"El atributo '" + PatronMapper.ATTR_PHOTO + "' debe ser de tipo byte[]. "
				+ "Tipo recibido: " + value.getClass().getName());
	}

	/**
	 * Determina si MidPoint solicito explicitamente el atributo {@code photo}
	 * en {@code OperationOptions.getAttributesToGet()}.
	 *
	 * @param options opciones de la operacion
	 * @return true si la foto fue solicitada de forma explicita
	 */
	private boolean isPhotoRequested(OperationOptions options) {
		if (options == null || options.getAttributesToGet() == null) {
			return false;
		}
		for (String attr : options.getAttributesToGet()) {
			if (PatronMapper.ATTR_PHOTO.equals(attr)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Si se solicito la foto y el canal JDBC esta operativo, lee la imagen de
	 * la tabla {@code patronimage} y la agrega al {@link ConnectorObject}.
	 *
	 * <p>Degradacion elegante: si el JDBC no esta operativo o el patron no tiene
	 * foto, el objeto se devuelve sin el atributo {@code photo}.</p>
	 *
	 * @param co         objeto del patron (puede ser null)
	 * @param fetchPhoto true si se debe leer la foto
	 * @return el objeto, enriquecido con la foto si corresponde
	 */
	private ConnectorObject enrichWithPhoto(ConnectorObject co, boolean fetchPhoto) {
		if (co == null || !fetchPhoto) {
			return co;
		}
		if (patronImageService == null) {
			LOG.warn("Se solicito la foto del patron {0} pero el canal JDBC no esta operativo.",
					co.getUid() != null ? co.getUid().getUidValue() : "?");
			return co;
		}
		String borrowernumber = co.getUid().getUidValue();
		byte[] photo = patronImageService.getImage(borrowernumber);
		if (photo == null || photo.length == 0) {
			return co;
		}
		// ConnId 1.5.2.0 no tiene constructor de copia en ConnectorObjectBuilder:
		// se reconstruye el objeto con sus atributos y se anade la foto.
		ConnectorObjectBuilder builder = new ConnectorObjectBuilder();
		builder.setObjectClass(co.getObjectClass());
		builder.addAttributes(co.getAttributes());
		builder.addAttribute(AttributeBuilder.build(PatronMapper.ATTR_PHOTO, photo));
		return builder.build();
	}
}