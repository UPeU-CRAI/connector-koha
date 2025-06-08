package com.identicum.connectors;

import com.identicum.connectors.mappers.CategoryMapper;
import com.identicum.connectors.mappers.PatronMapper;
import com.identicum.connectors.model.AttributeMetadata;
import com.identicum.connectors.services.CategoryService;
import com.identicum.connectors.services.PatronService;
import com.evolveum.polygon.rest.AbstractRestConnector;
import org.apache.http.impl.client.CloseableHttpClient;
import org.identityconnectors.common.logging.Log;
import org.identityconnectors.framework.common.exceptions.ConnectorIOException;
import org.identityconnectors.framework.common.objects.*;
import org.identityconnectors.framework.common.objects.filter.FilterTranslator;
import org.identityconnectors.framework.spi.ConnectorClass;
import org.identityconnectors.framework.spi.operations.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Set;

@ConnectorClass(displayNameKey = "connector.identicum.rest.display", configurationClass = KohaConfiguration.class)
public class KohaConnector
		extends AbstractRestConnector<KohaConfiguration>
		implements CreateOp, UpdateOp, SchemaOp, SearchOp<RestUsersFilter>, DeleteOp, TestOp {

	private static final Log LOG = Log.getLog(KohaConnector.class);

	private CloseableHttpClient httpClient;
	private PatronService patronService;
	private CategoryService categoryService;
	private final PatronMapper patronMapper = new PatronMapper();
	private final CategoryMapper categoryMapper = new CategoryMapper();

	private Schema connectorSchema = null;

	@Override
	public void init(org.identityconnectors.framework.spi.Configuration configuration) {
		super.init(configuration);
		LOG.ok("Inicializando componentes del conector...");
		KohaAuthenticator authenticator = new KohaAuthenticator(getConfiguration());
		this.httpClient = authenticator.createAuthenticatedClient();
		String serviceAddress = getConfiguration().getServiceAddress();
		this.patronService = new PatronService(this.httpClient, serviceAddress);
		this.categoryService = new CategoryService(this.httpClient, serviceAddress); // Re-introducido
		LOG.ok("Conector Koha inicializado con éxito.");
	}

	@Override
	public Schema schema() {
		if (this.connectorSchema != null) return this.connectorSchema;
		LOG.ok("Construyendo esquema para el Conector Koha...");
		SchemaBuilder schemaBuilder = new SchemaBuilder(KohaConnector.class);

		// --- Esquema para Cuentas (Patrones) ---
		ObjectClassInfoBuilder accountBuilder = new ObjectClassInfoBuilder();
		accountBuilder.setType(ObjectClass.ACCOUNT_NAME);
		accountBuilder.addAttributeInfo(AttributeInfoBuilder.define(Uid.NAME)
				.setNativeName(PatronMapper.KOHA_PATRON_ID_NATIVE_NAME).setType(String.class)
				.setRequired(true).setCreateable(false).setUpdateable(false).setReadable(true).build());
		AttributeMetadata nameMeta = PatronMapper.ATTRIBUTE_METADATA_MAP.get("userid");
		accountBuilder.addAttributeInfo(AttributeInfoBuilder.define(Name.NAME)
				.setNativeName(nameMeta.getKohaNativeName()).setType(String.class)
				.setRequired(nameMeta.isRequired()).build());
		for (AttributeMetadata meta : PatronMapper.ATTRIBUTE_METADATA_MAP.values()) {
			if (!"userid".equals(meta.getConnIdName())) {
				accountBuilder.addAttributeInfo(createAttributeInfo(meta));
			}
		}
		schemaBuilder.defineObjectClass(accountBuilder.build());

		// --- Esquema para Grupos (Categorías) ---
		ObjectClassInfoBuilder groupBuilder = new ObjectClassInfoBuilder();
		groupBuilder.setType(ObjectClass.GROUP_NAME);
		groupBuilder.addAttributeInfo(AttributeInfoBuilder.define(Uid.NAME)
				.setNativeName(CategoryMapper.KOHA_CATEGORY_ID_NATIVE_NAME).setType(String.class)
				.setRequired(true).setCreateable(false).setUpdateable(false).setReadable(true).build());
		AttributeMetadata catNameMeta = CategoryMapper.ATTRIBUTE_METADATA_MAP.get("name");
		groupBuilder.addAttributeInfo(AttributeInfoBuilder.define(Name.NAME)
				.setNativeName(catNameMeta.getKohaNativeName()).setType(String.class)
				.setRequired(catNameMeta.isRequired()).build());
		for (AttributeMetadata meta : CategoryMapper.ATTRIBUTE_METADATA_MAP.values()) {
			if (!"name".equals(meta.getConnIdName())) {
				groupBuilder.addAttributeInfo(createAttributeInfo(meta));
			}
		}
		schemaBuilder.defineObjectClass(groupBuilder.build());

		this.connectorSchema = schemaBuilder.build();
		LOG.ok("Esquema construido con éxito.");
		return this.connectorSchema;
	}

	@Override
	public Uid create(ObjectClass oClass, Set<Attribute> attrs, OperationOptions options) {
		try {
			if (ObjectClass.ACCOUNT.is(oClass.getObjectClassValue())) {
				JSONObject payload = patronMapper.buildPatronJson(attrs, true);
				JSONObject response = patronService.createPatron(payload);

				// Leemos el 'patron_id' de forma segura, sin asumir su tipo, y lo convertimos a String.
				String newUid = String.valueOf(response.get(PatronMapper.KOHA_PATRON_ID_NATIVE_NAME));
				return new Uid(newUid);

			} else if (ObjectClass.GROUP.is(oClass.getObjectClassValue())) {
				JSONObject payload = categoryMapper.buildCategoryJson(attrs, true);
				JSONObject response = categoryService.createCategory(payload);

				// Hacemos lo mismo para las categorías por consistencia.
				String newUid = String.valueOf(response.get(CategoryMapper.KOHA_CATEGORY_ID_NATIVE_NAME));
				return new Uid(newUid);

			} else {
				throw new UnsupportedOperationException("Operación Create no soportada para: " + oClass.getObjectClassValue());
			}
		} catch (IOException e) {
			throw new ConnectorIOException("Error en la operación CREATE: " + e.getMessage(), e);
		}
	}

	@Override
	public Uid update(ObjectClass oClass, Uid uid, Set<Attribute> attrs, OperationOptions options) {
		if (attrs == null || attrs.isEmpty()) return uid;
		try {
			if (ObjectClass.ACCOUNT.is(oClass.getObjectClassValue())) {
				JSONObject existingPatron = patronService.getPatron(uid.getUidValue());
				JSONObject changes = patronMapper.buildPatronJson(attrs, false);
				for (String key : changes.keySet()) {
					existingPatron.put(key, changes.get(key));
				}
				for (AttributeMetadata meta : PatronMapper.ATTRIBUTE_METADATA_MAP.values()) {
					if (meta.isNotUpdateable() || meta.isNotCreatable()) {
						existingPatron.remove(meta.getKohaNativeName());
					}
				}
				patronService.updatePatron(uid.getUidValue(), existingPatron);
				return uid;
			} else if (ObjectClass.GROUP.is(oClass.getObjectClassValue())) {
				JSONObject payload = categoryMapper.buildCategoryJson(attrs, false);
				categoryService.updateCategory(uid.getUidValue(), payload);
				return uid;
			} else {
				throw new UnsupportedOperationException("Operación Update no soportada para: " + oClass.getObjectClassValue());
			}
		} catch (IOException e) {
			throw new ConnectorIOException("Error en UPDATE: " + e.getMessage(), e);
		}
	}

	@Override
	public void delete(ObjectClass oClass, Uid uid, OperationOptions options) {
		try {
			if (ObjectClass.ACCOUNT.is(oClass.getObjectClassValue())) {
				patronService.deletePatron(uid.getUidValue());
			} else if (ObjectClass.GROUP.is(oClass.getObjectClassValue())) {
				categoryService.deleteCategory(uid.getUidValue());
			} else {
				throw new UnsupportedOperationException("Operación Delete no soportada para: " + oClass.getObjectClassValue());
			}
		} catch (IOException e) {
			throw new ConnectorIOException("Error en DELETE: " + e.getMessage(), e);
		}
	}

	@Override
	public void executeQuery(ObjectClass oClass, RestUsersFilter filter, ResultsHandler handler, OperationOptions options) {
		try {
			if (ObjectClass.ACCOUNT.is(oClass.getObjectClassValue())) {
				if (filter != null && filter.getByUid() != null) {
					JSONObject patronJson = patronService.getPatron(filter.getByUid());
					handler.handle(patronMapper.convertJsonToPatronObject(patronJson));
				} else {
					JSONArray results = patronService.searchPatrons(filter, options);
					for (int i = 0; i < results.length(); i++) {
						ConnectorObject co = patronMapper.convertJsonToPatronObject(results.getJSONObject(i));
						if (co != null && !handler.handle(co)) break;
					}
				}
			} else if (ObjectClass.GROUP.is(oClass.getObjectClassValue())) {
				if (filter != null && filter.getByUid() != null) {
					JSONObject categoryJson = categoryService.getCategory(filter.getByUid());
					handler.handle(categoryMapper.convertJsonToCategoryObject(categoryJson));
				} else {
					JSONArray results = categoryService.searchCategories(filter, options);
					for (int i = 0; i < results.length(); i++) {
						ConnectorObject co = categoryMapper.convertJsonToCategoryObject(results.getJSONObject(i));
						if (co != null && !handler.handle(co)) break;
					}
				}
			}
		} catch (IOException e) {
			throw new ConnectorIOException("Error en EXECUTE_QUERY: " + e.getMessage(), e);
		}
	}

	@Override
	public void test() {
		LOG.ok("Iniciando prueba de conexión...");
		try {
			patronService.searchPatrons(null, new OperationOptionsBuilder().setPageSize(1).build());
			LOG.ok("Prueba de conexión exitosa.");
		} catch (Exception e) {
			throw new ConnectorIOException("La prueba de conexión falló: " + e.getMessage(), e);
		}
	}

	@Override
	public FilterTranslator<RestUsersFilter> createFilterTranslator(ObjectClass oClass, OperationOptions options) {
		return new RestUsersFilterTranslator();
	}

	@Override
	public void dispose() {
		LOG.ok("Liberando recursos del Conector Koha...");
		try {
			if (httpClient != null) {
				httpClient.close();
			}
		} catch (IOException e) {
			LOG.error("Error al cerrar el cliente HTTP: {0}", e.getMessage(), e);
		}
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