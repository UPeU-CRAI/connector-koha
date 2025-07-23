package com.identicum.connectors;

import com.identicum.connectors.mappers.CategoryMapper;
import com.identicum.connectors.mappers.PatronMapper;
import com.identicum.connectors.model.AttributeMetadata;
import com.identicum.connectors.services.CategoryService;
import com.identicum.connectors.services.PatronService;
import org.identityconnectors.framework.spi.AbstractConnector;
import org.apache.http.impl.client.CloseableHttpClient;
import com.identicum.connectors.services.HttpClientAdapter;
import com.identicum.connectors.services.DefaultHttpClientAdapter;
import org.identityconnectors.common.logging.Log;
import org.identityconnectors.common.StringUtil;
import org.identityconnectors.framework.common.exceptions.ConfigurationException;
import org.identityconnectors.framework.common.exceptions.ConnectorException;
import org.identityconnectors.framework.common.exceptions.ConnectorIOException;
import org.identityconnectors.framework.common.objects.Attribute;
import org.identityconnectors.framework.common.objects.AttributeInfo;
import org.identityconnectors.framework.common.objects.AttributeInfoBuilder;
import org.identityconnectors.framework.common.objects.ConnectorObject;
import org.identityconnectors.framework.common.objects.Name;
import org.identityconnectors.framework.common.objects.ObjectClass;
import org.identityconnectors.framework.common.objects.ObjectClassInfo;
import org.identityconnectors.framework.common.objects.ObjectClassInfoBuilder;
import org.identityconnectors.framework.common.objects.OperationOptions;
import org.identityconnectors.framework.common.objects.OperationOptionsBuilder;
import org.identityconnectors.framework.common.objects.ResultsHandler;
import org.identityconnectors.framework.common.objects.Schema;
import org.identityconnectors.framework.common.objects.SchemaBuilder;
import org.identityconnectors.framework.common.objects.Uid;
import org.identityconnectors.framework.common.objects.filter.FilterTranslator;
import org.identityconnectors.framework.spi.ConnectorClass;
import org.identityconnectors.framework.spi.operations.CreateOp;
import org.identityconnectors.framework.spi.operations.DeleteOp;
import org.identityconnectors.framework.spi.operations.SchemaOp;
import org.identityconnectors.framework.spi.operations.SearchOp;
import org.identityconnectors.framework.spi.operations.TestOp;
import org.identityconnectors.framework.spi.operations.UpdateOp;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@ConnectorClass(displayNameKey = "connector.identicum.rest.display", configurationClass = KohaConfiguration.class)
public class KohaConnector
                extends AbstractConnector<KohaConfiguration>
		implements CreateOp, UpdateOp, SchemaOp, SearchOp<KohaFilter>, DeleteOp, TestOp {

	private static final Log LOG = Log.getLog(KohaConnector.class);

        private CloseableHttpClient httpClient;
        private HttpClientAdapter httpAdapter;
	private PatronService patronService;
	private CategoryService categoryService;
	private final PatronMapper patronMapper = new PatronMapper();
	private final CategoryMapper categoryMapper = new CategoryMapper();

	private Schema connectorSchema = null;

	@Override
	public void init(org.identityconnectors.framework.spi.Configuration configuration) {
		super.init(configuration);

		if (StringUtil.isBlank(getConfiguration().getServiceAddress())) {
			throw new ConfigurationException("Service address (serviceAddress) must be provided.");
		}

		String authenticationMethodStrategy = getConfiguration().getAuthenticationMethodStrategy();

		if (StringUtil.isBlank(authenticationMethodStrategy)) {
			throw new ConfigurationException("Authentication method (authenticationMethodStrategy) must be provided.");
		}

                if ("BASIC".equalsIgnoreCase(authenticationMethodStrategy)) {
                        if (StringUtil.isBlank(getConfiguration().getUsername())) {
                                throw new ConfigurationException("Username must be provided for BASIC authentication.");
                        }
                        if (StringUtil.isBlank(getConfiguration().getPassword())) {
                                throw new ConfigurationException("Password must be provided for BASIC authentication.");
                        }
                } else if ("OAUTH2".equalsIgnoreCase(authenticationMethodStrategy)) {
                        if (StringUtil.isBlank(getConfiguration().getClientId())) {
                                throw new ConfigurationException("Client ID must be provided for OAUTH2 authentication.");
                        }
                        if (StringUtil.isBlank(getConfiguration().getClientSecret())) {
                                throw new ConfigurationException("Client Secret must be provided for OAUTH2 authentication.");
                        }
                } else {
                        throw new ConfigurationException("Invalid authentication method (authenticationMethodStrategy) specified. Must be 'BASIC' or 'OAUTH2'.");
                }

		LOG.ok("Inicializando componentes del conector...");
                KohaAuthenticator authenticator = new KohaAuthenticator(getConfiguration());
                this.httpClient = authenticator.createAuthenticatedClient();
                this.httpAdapter = new DefaultHttpClientAdapter(this.httpClient);

		try {
                        String serviceAddress = getConfiguration().getServiceAddress();
                        this.patronService = new PatronService(this.httpAdapter, serviceAddress);
                        this.categoryService = new CategoryService(this.httpAdapter, serviceAddress);
			LOG.ok("Conector Koha inicializado con éxito.");
		} catch (Exception e) { // Catch any exception during service initialization
                        LOG.error(e, "Error durante la inicialización de los servicios del conector después de crear httpClient.");
                        if (this.httpAdapter != null) {
                                try {
                                        this.httpAdapter.close();
                                        LOG.info("Cliente HTTP cerrado debido a un error durante la inicialización tardía del conector.");
                                } catch (IOException ioe) {
					LOG.error(ioe, "Error al intentar cerrar el cliente HTTP durante el manejo de errores de init.");
					// Do not re-throw ioe here to avoid masking the original exception 'e'.
					// Add 'e' as a suppressed exception to 'ioe' if desired, or log 'e' separately.
					// For now, just logging, original 'e' will be wrapped and thrown.
				}
			}
			// Wrap and rethrow the original exception 'e'
			throw new ConfigurationException("Fallo durante la inicialización de los servicios del conector: " + e.getMessage(), e);
		}
	}

	@Override
	public Schema schema() {
		if (this.connectorSchema != null) return this.connectorSchema;
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

		this.connectorSchema = schemaBuilder.build();
		LOG.ok("Esquema construido con éxito.");
		return this.connectorSchema;
	}

	@Override
	public Uid create(ObjectClass oClass, Set<Attribute> attrs, OperationOptions options) {
		LOG.ok("Iniciando Create para ObjectClass {0}, Atributos: {1}", oClass, attrs != null ? attrs.stream().map(Attribute::getName).collect(Collectors.toSet()) : "null");
		String newUidValue = null;
		try {
			if (ObjectClass.ACCOUNT.is(oClass.getObjectClassValue())) {
				JSONObject payload = patronMapper.buildPatronJson(attrs, true);
				JSONObject response = patronService.createPatron(payload);
				newUidValue = String.valueOf(response.get(PatronMapper.KOHA_PATRON_ID_NATIVE_NAME));
			} else if (ObjectClass.GROUP.is(oClass.getObjectClassValue())) {
				JSONObject payload = categoryMapper.buildCategoryJson(attrs, true);
				JSONObject response = categoryService.createCategory(payload);
				newUidValue = String.valueOf(response.get(CategoryMapper.KOHA_CATEGORY_ID_NATIVE_NAME));
			} else {
				throw new UnsupportedOperationException("Operación Create no soportada para: " + oClass.getObjectClassValue());
			}
			LOG.ok("Create para ObjectClass {0} completado. Uid: {1}", oClass, newUidValue);
			return new Uid(newUidValue);
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
				// Consider fetching only if necessary or let service handle optimistic locking if supported
				// For now, matching existing logic structure:
				JSONObject existingPatron = patronService.getPatron(uid.getUidValue()); // This could throw if not found
				JSONObject changes = patronMapper.buildPatronJson(attrs, false);
				for (String key : changes.keySet()) {
					existingPatron.put(key, changes.get(key));
				}
				// Prune attributes that are not updateable according to metadata
				// This logic might be better suited within the mapper or service
				for (AttributeMetadata meta : PatronMapper.ATTRIBUTE_METADATA_MAP.values()) {
					if (meta.isNotUpdateable()) { // Only check NotUpdateable for update
						existingPatron.remove(meta.getKohaNativeName());
					}
				}
				patronService.updatePatron(uid.getUidValue(), existingPatron);
			} else if (ObjectClass.GROUP.is(oClass.getObjectClassValue())) {
				JSONObject payload = categoryMapper.buildCategoryJson(attrs, false);
				// Similar pruning could be applied for groups if necessary
				categoryService.updateCategory(uid.getUidValue(), payload);
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
				categoryService.deleteCategory(uid.getUidValue());
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
				if (filter != null && filter.getByUid() != null) {
					JSONObject patronJson = patronService.getPatron(filter.getByUid());
					if (patronJson != null && patronJson.length() > 0) { // Check if patronJson is not null or empty
						handler.handle(patronMapper.convertJsonToPatronObject(patronJson));
						LOG.info("Resultados de búsqueda por UID para {0}: 1", oClass);
					} else {
						LOG.info("Resultados de búsqueda por UID para {0}: 0 (Patrón no encontrado o vacío)", oClass);
					}
				} else {
					JSONArray results = patronService.searchPatrons(filter, options);
					LOG.info("Resultados de búsqueda para {0}: {1}", oClass, results != null ? results.length() : 0);
					if (results != null) {
						for (int i = 0; i < results.length(); i++) {
							ConnectorObject co = patronMapper.convertJsonToPatronObject(results.getJSONObject(i));
							if (co != null && !handler.handle(co)) break;
						}
					}
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

			// Paso 2: Probar búsqueda básica de patrones (conectividad y autenticación)
			LOG.ok("Paso 2/2: Probando búsqueda básica de patrones (conectividad y autenticación)...");
			patronService.searchPatrons(null, new OperationOptionsBuilder().setPageSize(1).build());

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
	}

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
		return builder.build();
	}
}
