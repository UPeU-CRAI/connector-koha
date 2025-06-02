package com.identicum.connectors;

import com.evolveum.polygon.rest.AbstractRestConnector;
import org.apache.commons.codec.binary.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.*;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.identityconnectors.common.StringUtil;
import org.identityconnectors.common.logging.Log;
import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.common.exceptions.*;
import org.identityconnectors.framework.common.objects.*;
import org.identityconnectors.framework.common.objects.filter.FilterTranslator;
import org.identityconnectors.framework.spi.ConnectorClass;
import org.identityconnectors.framework.spi.operations.CreateOp;
import org.identityconnectors.framework.spi.operations.DeleteOp;
import org.identityconnectors.framework.spi.operations.SchemaOp;
import org.identityconnectors.framework.spi.operations.SearchOp;
import org.identityconnectors.framework.spi.operations.TestOp;
import org.identityconnectors.framework.spi.operations.UpdateOp;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
// Los imports para PagedResultsCookie y PagedResultsHandler han sido eliminados

@ConnectorClass(displayNameKey = "connector.identicum.rest.display", configurationClass = RestUsersConfiguration.class)
public class RestUsersConnector
		extends AbstractRestConnector<RestUsersConfiguration>
		implements CreateOp, UpdateOp, SchemaOp, SearchOp<RestUsersFilter>, DeleteOp, TestOp {

	private RestUsersConfiguration configuration;
	// === Campos para manejo del token OAuth2 ===
	private String oauthAccessToken;
	private long oauthTokenExpiryEpoch = 0L;

	private static final Log LOG = Log.getLog(RestUsersConnector.class);

	private static final String API_BASE_PATH = "/api/v1";
	private static final String PATRONS_ENDPOINT_SUFFIX = "/patrons";
	private static final String CATEGORIES_ENDPOINT_SUFFIX = "/patron_categories";
	private static final String KOHA_OAUTH_TOKEN_ENDPOINT_SUFFIX = "/api/v1/oauth/token";

	public static final String KOHA_PATRON_ID_NATIVE_NAME = "patron_id";
	public static final String KOHA_CATEGORY_ID_NATIVE_NAME = "patron_category_id"; // ¡CONFIRMAR!

	public static final String ATTR_USERID = "userid";
	public static final String ATTR_CARDNUMBER = "cardnumber";
	public static final String ATTR_SURNAME = "surname";
	public static final String ATTR_FIRSTNAME = "firstname";
	public static final String ATTR_EMAIL = "email";
	public static final String ATTR_PHONE = "phone";
	public static final String ATTR_MOBILE = "mobile";
	public static final String ATTR_LIBRARY_ID = "library_id";
	public static final String ATTR_CATEGORY_ID = "category_id";
	public static final String ATTR_DATE_OF_BIRTH = "date_of_birth";
	public static final String ATTR_EXPIRY_DATE = "expiry_date";
	public static final String ATTR_DATE_ENROLLED = "date_enrolled";
	public static final String ATTR_DATE_RENEWED = "date_renewed";
	public static final String ATTR_GENDER = "gender";
	public static final String ATTR_ADDRESS = "address";
	public static final String ATTR_ADDRESS2 = "address2";
	public static final String ATTR_CITY = "city";
	public static final String ATTR_STATE = "state";
	public static final String ATTR_POSTAL_CODE = "postal_code";
	public static final String ATTR_COUNTRY = "country";
	public static final String ATTR_STAFF_NOTES = "staff_notes";
	public static final String ATTR_OPAC_NOTES = "opac_notes";
	public static final String ATTR_INCORRECT_ADDRESS = "incorrect_address";
	public static final String ATTR_PATRON_CARD_LOST = "patron_card_lost";
	public static final String ATTR_EXPIRED = "expired";
	public static final String ATTR_RESTRICTED = "restricted";
	public static final String ATTR_AUTORENEW_CHECKOUTS = "autorenew_checkouts";
	public static final String ATTR_ANONYMIZED = "anonymized";
	public static final String ATTR_PROTECTED = "protected";
	public static final String ATTR_UPDATED_ON = "updated_on";
	public static final String ATTR_LAST_SEEN = "last_seen";

	public static final String ATTR_CATEGORY_DESCRIPTION = "description"; // ¡CONFIRMAR!
	public static final String ATTR_CATEGORY_TYPE = "category_type";
	public static final String ATTR_CATEGORY_ENROLMENT_PERIOD = "enrolment_period";

	private static final DateTimeFormatter KOHA_DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;
	private static final DateTimeFormatter KOHA_DATETIME_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

	private volatile Schema connectorSchema = null;
	private String oauthAccessToken = null;

	private static class AttributeMetadata {
		final String connIdName; final String kohaNativeName; final Class<?> type; final Set<Flags> flags;
		enum Flags {REQUIRED, NOT_CREATABLE, NOT_UPDATEABLE, NOT_READABLE, MULTIVALUED}
		AttributeMetadata(String connIdName, String kohaNativeName, Class<?> type, Flags... flags) {
			this.connIdName = connIdName; this.kohaNativeName = kohaNativeName; this.type = type;
			this.flags = flags == null ? Collections.emptySet() : new HashSet<>(List.of(flags));
		}
	}

	private static final Map<String, AttributeMetadata> KOHA_PATRON_ATTRIBUTE_METADATA = new HashMap<>();
	static {
		KOHA_PATRON_ATTRIBUTE_METADATA.put(ATTR_USERID, new AttributeMetadata(ATTR_USERID, "userid", String.class, AttributeMetadata.Flags.REQUIRED));
		KOHA_PATRON_ATTRIBUTE_METADATA.put(ATTR_CARDNUMBER, new AttributeMetadata(ATTR_CARDNUMBER, "cardnumber", String.class, AttributeMetadata.Flags.REQUIRED));
		KOHA_PATRON_ATTRIBUTE_METADATA.put(ATTR_SURNAME, new AttributeMetadata(ATTR_SURNAME, "surname", String.class, AttributeMetadata.Flags.REQUIRED));
		KOHA_PATRON_ATTRIBUTE_METADATA.put(ATTR_FIRSTNAME, new AttributeMetadata(ATTR_FIRSTNAME, "firstname", String.class));
		KOHA_PATRON_ATTRIBUTE_METADATA.put(ATTR_EMAIL, new AttributeMetadata(ATTR_EMAIL, "email", String.class));
		KOHA_PATRON_ATTRIBUTE_METADATA.put(ATTR_PHONE, new AttributeMetadata(ATTR_PHONE, "phone", String.class));
		KOHA_PATRON_ATTRIBUTE_METADATA.put(ATTR_MOBILE, new AttributeMetadata(ATTR_MOBILE, "mobile", String.class));
		KOHA_PATRON_ATTRIBUTE_METADATA.put(ATTR_LIBRARY_ID, new AttributeMetadata(ATTR_LIBRARY_ID, "library_id", String.class, AttributeMetadata.Flags.REQUIRED));
		KOHA_PATRON_ATTRIBUTE_METADATA.put(ATTR_CATEGORY_ID, new AttributeMetadata(ATTR_CATEGORY_ID, "category_id", String.class, AttributeMetadata.Flags.REQUIRED));
		KOHA_PATRON_ATTRIBUTE_METADATA.put(ATTR_GENDER, new AttributeMetadata(ATTR_GENDER, "gender", String.class));
		KOHA_PATRON_ATTRIBUTE_METADATA.put(ATTR_ADDRESS, new AttributeMetadata(ATTR_ADDRESS, "address", String.class));
		KOHA_PATRON_ATTRIBUTE_METADATA.put(ATTR_ADDRESS2, new AttributeMetadata(ATTR_ADDRESS2, "address2", String.class));
		KOHA_PATRON_ATTRIBUTE_METADATA.put(ATTR_CITY, new AttributeMetadata(ATTR_CITY, "city", String.class));
		KOHA_PATRON_ATTRIBUTE_METADATA.put(ATTR_STATE, new AttributeMetadata(ATTR_STATE, "state", String.class));
		KOHA_PATRON_ATTRIBUTE_METADATA.put(ATTR_POSTAL_CODE, new AttributeMetadata(ATTR_POSTAL_CODE, "postal_code", String.class));
		KOHA_PATRON_ATTRIBUTE_METADATA.put(ATTR_COUNTRY, new AttributeMetadata(ATTR_COUNTRY, "country", String.class));
		KOHA_PATRON_ATTRIBUTE_METADATA.put(ATTR_STAFF_NOTES, new AttributeMetadata(ATTR_STAFF_NOTES, "staff_notes", String.class));
		KOHA_PATRON_ATTRIBUTE_METADATA.put(ATTR_OPAC_NOTES, new AttributeMetadata(ATTR_OPAC_NOTES, "opac_notes", String.class));
		KOHA_PATRON_ATTRIBUTE_METADATA.put(ATTR_DATE_OF_BIRTH, new AttributeMetadata(ATTR_DATE_OF_BIRTH, "date_of_birth", String.class));
		KOHA_PATRON_ATTRIBUTE_METADATA.put(ATTR_EXPIRY_DATE, new AttributeMetadata(ATTR_EXPIRY_DATE, "expiry_date", String.class));
		KOHA_PATRON_ATTRIBUTE_METADATA.put(ATTR_DATE_ENROLLED, new AttributeMetadata(ATTR_DATE_ENROLLED, "date_enrolled", String.class, AttributeMetadata.Flags.NOT_CREATABLE, AttributeMetadata.Flags.NOT_UPDATEABLE));
		KOHA_PATRON_ATTRIBUTE_METADATA.put(ATTR_DATE_RENEWED, new AttributeMetadata(ATTR_DATE_RENEWED, "date_renewed", String.class, AttributeMetadata.Flags.NOT_CREATABLE, AttributeMetadata.Flags.NOT_UPDATEABLE));
		KOHA_PATRON_ATTRIBUTE_METADATA.put(ATTR_INCORRECT_ADDRESS, new AttributeMetadata(ATTR_INCORRECT_ADDRESS, "incorrect_address", Boolean.class));
		KOHA_PATRON_ATTRIBUTE_METADATA.put(ATTR_PATRON_CARD_LOST, new AttributeMetadata(ATTR_PATRON_CARD_LOST, "patron_card_lost", Boolean.class));
		KOHA_PATRON_ATTRIBUTE_METADATA.put(ATTR_EXPIRED, new AttributeMetadata(ATTR_EXPIRED, "expired", Boolean.class, AttributeMetadata.Flags.NOT_CREATABLE, AttributeMetadata.Flags.NOT_UPDATEABLE));
		KOHA_PATRON_ATTRIBUTE_METADATA.put(ATTR_RESTRICTED, new AttributeMetadata(ATTR_RESTRICTED, "restricted", Boolean.class));
		KOHA_PATRON_ATTRIBUTE_METADATA.put(ATTR_AUTORENEW_CHECKOUTS, new AttributeMetadata(ATTR_AUTORENEW_CHECKOUTS, "autorenew_checkouts", Boolean.class));
		KOHA_PATRON_ATTRIBUTE_METADATA.put(ATTR_ANONYMIZED, new AttributeMetadata(ATTR_ANONYMIZED, "anonymized", Boolean.class, AttributeMetadata.Flags.NOT_CREATABLE, AttributeMetadata.Flags.NOT_UPDATEABLE));
		KOHA_PATRON_ATTRIBUTE_METADATA.put(ATTR_PROTECTED, new AttributeMetadata(ATTR_PROTECTED, "protected", Boolean.class));
		KOHA_PATRON_ATTRIBUTE_METADATA.put(ATTR_UPDATED_ON, new AttributeMetadata(ATTR_UPDATED_ON, "updated_on", String.class, AttributeMetadata.Flags.NOT_CREATABLE, AttributeMetadata.Flags.NOT_UPDATEABLE));
		KOHA_PATRON_ATTRIBUTE_METADATA.put(ATTR_LAST_SEEN, new AttributeMetadata(ATTR_LAST_SEEN, "last_seen", String.class, AttributeMetadata.Flags.NOT_CREATABLE, AttributeMetadata.Flags.NOT_UPDATEABLE));
	}
	private static final Set<String> MANAGED_KOHA_PATRON_CONNID_NAMES = Collections.unmodifiableSet(new HashSet<>(Set.of(
			ATTR_USERID, ATTR_CARDNUMBER, ATTR_SURNAME, ATTR_FIRSTNAME, ATTR_EMAIL, ATTR_PHONE,
			ATTR_LIBRARY_ID, ATTR_CATEGORY_ID, ATTR_DATE_OF_BIRTH, ATTR_EXPIRY_DATE,
			ATTR_GENDER, ATTR_ADDRESS, ATTR_CITY, ATTR_STATE, ATTR_POSTAL_CODE, ATTR_COUNTRY,
			ATTR_STAFF_NOTES, ATTR_OPAC_NOTES,
			ATTR_INCORRECT_ADDRESS, ATTR_PATRON_CARD_LOST, ATTR_RESTRICTED, ATTR_AUTORENEW_CHECKOUTS,
			ATTR_PROTECTED
	)));
	private static final Map<String, AttributeMetadata> KOHA_CATEGORY_ATTRIBUTE_METADATA = new HashMap<>();
	static {
		KOHA_CATEGORY_ATTRIBUTE_METADATA.put(ATTR_CATEGORY_DESCRIPTION, new AttributeMetadata(ATTR_CATEGORY_DESCRIPTION, "description", String.class, AttributeMetadata.Flags.REQUIRED));
		KOHA_CATEGORY_ATTRIBUTE_METADATA.put(ATTR_CATEGORY_TYPE, new AttributeMetadata(ATTR_CATEGORY_TYPE, "category_type", String.class));
		KOHA_CATEGORY_ATTRIBUTE_METADATA.put(ATTR_CATEGORY_ENROLMENT_PERIOD, new AttributeMetadata(ATTR_CATEGORY_ENROLMENT_PERIOD, "enrolment_period", String.class));
	}
	private static final Set<String> MANAGED_KOHA_CATEGORY_CONNID_NAMES = Collections.unmodifiableSet(new HashSet<>(Set.of(
			ATTR_CATEGORY_DESCRIPTION, ATTR_CATEGORY_TYPE, ATTR_CATEGORY_ENROLMENT_PERIOD
	)));

	@Override
	public void init(org.identityconnectors.framework.spi.Configuration configuration) {
		super.init(configuration);
		this.oauthAccessToken = null;
		RestUsersConfiguration currentConfig = getConfiguration();
		if (currentConfig == null) {
			throw new ConfigurationException("Connector configuration is null after super.init()");
		}
		LOG.ok("Koha Connector initialized. ServiceAddress: {0}, AuthMethod: {1}", currentConfig.getServiceAddress(), currentConfig.getAuthMethod());
		if (StringUtil.isNotBlank(currentConfig.getClientId())) {
			if (StringUtil.isBlank(currentConfig.getServiceAddress())) {
				throw new ConfigurationException("Service Address (URL Base) must be configured when Client ID is present for OAuth2.");
			}
			if (currentConfig.getClientSecret() == null) {
				throw new ConfigurationException("Client Secret must be configured when Client ID is present for OAuth2.");
			}
		}
	}

	@Override
	public Schema schema() {
		if (this.connectorSchema != null) return this.connectorSchema;

		LOG.ok("Building schema for Koha Connector");
		SchemaBuilder schemaBuilder = new SchemaBuilder(RestUsersConnector.class);

		// ========= PATRONS (ACCOUNT) ==========
		ObjectClassInfoBuilder accountBuilder = new ObjectClassInfoBuilder();
		accountBuilder.setType(ObjectClass.ACCOUNT_NAME);
		Set<String> accountAttrsDefined = new HashSet<>();

		// UID (patron ID)
		accountBuilder.addAttributeInfo(
				AttributeInfoBuilder.define(Uid.NAME)
						.setNativeName(KOHA_PATRON_ID_NATIVE_NAME)
						.setType(String.class)
						.setRequired(true)
						.setCreateable(false)
						.setUpdateable(false)
						.setReadable(true)
						.build()
		);
		accountAttrsDefined.add(Uid.NAME);

		// NAME (usually userid)
		AttributeMetadata nameAttrMeta = KOHA_PATRON_ATTRIBUTE_METADATA.get(ATTR_USERID);
		if (nameAttrMeta != null && !accountAttrsDefined.contains(Name.NAME)) {
			accountBuilder.addAttributeInfo(
					AttributeInfoBuilder.define(Name.NAME)
							.setNativeName(nameAttrMeta.kohaNativeName)
							.setType(nameAttrMeta.type)
							.setRequired(true)
							.setCreateable(!nameAttrMeta.flags.contains(AttributeMetadata.Flags.NOT_CREATABLE))
							.setUpdateable(!nameAttrMeta.flags.contains(AttributeMetadata.Flags.NOT_UPDATEABLE))
							.setReadable(!nameAttrMeta.flags.contains(AttributeMetadata.Flags.NOT_READABLE))
							.build()
			);
			accountAttrsDefined.add(Name.NAME);
		}

		// Patron attributes
		for (String connIdAttrName : MANAGED_KOHA_PATRON_CONNID_NAMES) {
			if (accountAttrsDefined.contains(connIdAttrName)) continue;
			AttributeMetadata meta = KOHA_PATRON_ATTRIBUTE_METADATA.get(connIdAttrName);
			if (meta != null) {
				accountBuilder.addAttributeInfo(
						AttributeInfoBuilder.define(meta.connIdName)
								.setNativeName(meta.kohaNativeName)
								.setType(meta.type)
								.setRequired(meta.flags.contains(AttributeMetadata.Flags.REQUIRED))
								.setMultiValued(meta.flags.contains(AttributeMetadata.Flags.MULTIVALUED))
								.setCreateable(!meta.flags.contains(AttributeMetadata.Flags.NOT_CREATABLE))
								.setUpdateable(!meta.flags.contains(AttributeMetadata.Flags.NOT_UPDATEABLE))
								.setReadable(!meta.flags.contains(AttributeMetadata.Flags.NOT_READABLE))
								.build()
				);
				accountAttrsDefined.add(meta.connIdName);
			} else {
				LOG.warn("Schema: Metadata not found for managed patron attribute '{0}'. Skipping.", connIdAttrName);
			}
		}

		// Patron attributes (read-only extras)
		KOHA_PATRON_ATTRIBUTE_METADATA.forEach((connIdName, meta) -> {
			if (!accountAttrsDefined.contains(connIdName)
					&& meta.flags.contains(AttributeMetadata.Flags.NOT_CREATABLE)
					&& meta.flags.contains(AttributeMetadata.Flags.NOT_UPDATEABLE)) {
				accountBuilder.addAttributeInfo(
						AttributeInfoBuilder.define(meta.connIdName)
								.setNativeName(meta.kohaNativeName)
								.setType(meta.type)
								.setMultiValued(meta.flags.contains(AttributeMetadata.Flags.MULTIVALUED))
								.setCreateable(false)
								.setUpdateable(false)
								.setReadable(true)
								.build()
				);
				accountAttrsDefined.add(connIdName);
			}
		});

		schemaBuilder.defineObjectClass(accountBuilder.build());

		// ========== CATEGORIES (GROUP) ==========
		ObjectClassInfoBuilder groupBuilder = new ObjectClassInfoBuilder();
		groupBuilder.setType(ObjectClass.GROUP_NAME);
		Set<String> groupAttrsDefined = new HashSet<>();

		// UID for group
		groupBuilder.addAttributeInfo(
				AttributeInfoBuilder.define(Uid.NAME)
						.setNativeName(KOHA_CATEGORY_ID_NATIVE_NAME)
						.setType(String.class)
						.setRequired(true)
						.setCreateable(false)
						.setUpdateable(false)
						.setReadable(true)
						.build()
		);
		groupAttrsDefined.add(Uid.NAME);

		// NAME for group (mapped only if not duplicated)
		AttributeMetadata groupNameMeta = KOHA_CATEGORY_ATTRIBUTE_METADATA.get(ATTR_CATEGORY_DESCRIPTION);
		if (groupNameMeta != null && !"description".equalsIgnoreCase(groupNameMeta.connIdName)) {
			groupBuilder.addAttributeInfo(
					AttributeInfoBuilder.define(Name.NAME)
							.setNativeName(groupNameMeta.kohaNativeName)
							.setType(groupNameMeta.type)
							.setRequired(true)
							.setCreateable(!groupNameMeta.flags.contains(AttributeMetadata.Flags.NOT_CREATABLE))
							.setUpdateable(!groupNameMeta.flags.contains(AttributeMetadata.Flags.NOT_UPDATEABLE))
							.setReadable(!groupNameMeta.flags.contains(AttributeMetadata.Flags.NOT_READABLE))
							.build()
			);
			groupAttrsDefined.add(Name.NAME);
		} else {
			LOG.warn("Schema: Skipping Name.NAME mapping for 'description' to avoid duplication.");
		}

		// Category attributes
		for (String connIdAttrName : MANAGED_KOHA_CATEGORY_CONNID_NAMES) {
			if (groupAttrsDefined.contains(connIdAttrName)) continue;
			AttributeMetadata meta = KOHA_CATEGORY_ATTRIBUTE_METADATA.get(connIdAttrName);
			if (meta != null) {
				groupBuilder.addAttributeInfo(
						AttributeInfoBuilder.define(meta.connIdName)
								.setNativeName(meta.kohaNativeName)
								.setType(meta.type)
								.setRequired(meta.flags.contains(AttributeMetadata.Flags.REQUIRED))
								.setMultiValued(meta.flags.contains(AttributeMetadata.Flags.MULTIVALUED))
								.setCreateable(!meta.flags.contains(AttributeMetadata.Flags.NOT_CREATABLE))
								.setUpdateable(!meta.flags.contains(AttributeMetadata.Flags.NOT_UPDATEABLE))
								.setReadable(!meta.flags.contains(AttributeMetadata.Flags.NOT_READABLE))
								.build()
				);
				groupAttrsDefined.add(meta.connIdName);
			} else {
				LOG.warn("Schema: Metadata not found for managed category attribute '{0}'. Skipping.", connIdAttrName);
			}
		}

		// Read-only category attributes
		KOHA_CATEGORY_ATTRIBUTE_METADATA.forEach((connIdName, meta) -> {
			if (!groupAttrsDefined.contains(connIdName)
					&& meta.flags.contains(AttributeMetadata.Flags.NOT_CREATABLE)
					&& meta.flags.contains(AttributeMetadata.Flags.NOT_UPDATEABLE)) {
				groupBuilder.addAttributeInfo(
						AttributeInfoBuilder.define(meta.connIdName)
								.setNativeName(meta.kohaNativeName)
								.setType(meta.type)
								.setMultiValued(meta.flags.contains(AttributeMetadata.Flags.MULTIVALUED))
								.setCreateable(false)
								.setUpdateable(false)
								.setReadable(true)
								.build()
				);
				groupAttrsDefined.add(connIdName);
			}
		});
		schemaBuilder.defineObjectClass(groupBuilder.build());
		this.connectorSchema = schemaBuilder.build();
		LOG.ok("Schema built successfully.");
		return this.connectorSchema;
	}

	private String getFreshAuthToken() throws IOException {
		RestUsersConfiguration config = getConfiguration();

		// === Validación básica ===
		String serviceAddr = config.getServiceAddress();
		String clientId = config.getClientId();
		GuardedString clientSecretGuarded = config.getClientSecret();

		if (StringUtil.isBlank(serviceAddr) || StringUtil.isBlank(clientId) || clientSecretGuarded == null) {
			LOG.error("AUTH_OAUTH: Parámetros de OAuth2 incompletos: serviceAddress, clientId o clientSecret.");
			throw new ConfigurationException("Debe configurar correctamente Service Address, Client ID y Client Secret para usar OAuth2.");
		}

		// === Extracción segura del clientSecret ===
		final StringBuilder secretBuilder = new StringBuilder();
		clientSecretGuarded.access(secretBuilder::append);
		String clientSecret = secretBuilder.toString();

		// === Construcción del endpoint y la petición ===
		String tokenEndpoint = serviceAddr + KOHA_OAUTH_TOKEN_ENDPOINT_SUFFIX; // asegúrate que este valor sea "/oauth/token"
		LOG.ok("AUTH_OAUTH: Solicitando token OAuth2 a {0}", tokenEndpoint);

		HttpPost tokenRequest = new HttpPost(tokenEndpoint);
		List<NameValuePair> params = Arrays.asList(
				new BasicNameValuePair("grant_type", "client_credentials"),
				new BasicNameValuePair("client_id", clientId),
				new BasicNameValuePair("client_secret", clientSecret)
		);

		tokenRequest.setEntity(new UrlEncodedFormEntity(params, StandardCharsets.UTF_8));
		tokenRequest.setHeader("Content-Type", "application/x-www-form-urlencoded");
		tokenRequest.setHeader("Accept", "application/json");

		// === Ejecución de la petición ===
		String responseBodyForErrorLog = "N/A";
		try (CloseableHttpResponse response = getHttpClient().execute(tokenRequest)) {
			int statusCode = response.getStatusLine().getStatusCode();
			HttpEntity entity = response.getEntity();
			String responseBody = entity != null ? EntityUtils.toString(entity, StandardCharsets.UTF_8) : null;
			responseBodyForErrorLog = responseBody;

			if (statusCode >= 200 && statusCode < 300) {
				JSONObject jsonResponse = new JSONObject(responseBody);
				if (jsonResponse.has("access_token")) {
					String obtainedToken = jsonResponse.getString("access_token");
					LOG.ok("AUTH_OAUTH: Token obtenido exitosamente.");
					return obtainedToken;
				} else {
					LOG.error("AUTH_OAUTH: Respuesta JSON no contiene 'access_token': {0}", responseBody);
					throw new ConnectorIOException("Token OAuth2 no encontrado en la respuesta.");
				}
			} else {
				LOG.error("AUTH_OAUTH: Falló la obtención del token. Status: {0}, Respuesta: {1}", statusCode, responseBody);
				throw new ConnectorIOException("Error al obtener token OAuth2. Código: " + statusCode);
			}
		} catch (JSONException e) {
			LOG.error("AUTH_OAUTH: Error interpretando JSON. Respuesta: {0}", responseBodyForErrorLog, e);
			throw new ConnectorException("Error procesando respuesta JSON del token endpoint: " + e.getMessage(), e);
		}
	}


	private void addAuthHeader(HttpRequestBase request) throws IOException {
		RestUsersConfiguration config = getConfiguration();

		// ======== OAuth2 Client Credentials ========
		if (StringUtil.isNotBlank(config.getClientId()) && config.getClientSecret() != null) {
			LOG.ok("AUTH: Intentando autenticación OAuth2 (Client Credentials)");
			this.oauthAccessToken = getFreshAuthToken();
			if (StringUtil.isNotBlank(this.oauthAccessToken)) {
				request.setHeader("Authorization", "Bearer " + this.oauthAccessToken);
				LOG.ok("AUTH: Usando token Bearer para la solicitud: {0}", request.getURI());
				return;
			} else {
				LOG.error("AUTH: Falló la obtención de token OAuth2 para la URI: {0}", request.getURI());
				throw new PermissionDeniedException("Configurado OAuth2, pero no se pudo obtener token válido.");
			}
		}

		// ======== Basic Auth ========
		if ("BASIC".equalsIgnoreCase(config.getAuthMethod())) {
			String username = config.getUsername();
			GuardedString passwordGuarded = config.getPassword();

			if (StringUtil.isNotBlank(username) && passwordGuarded != null) {
				StringBuilder passwordBuilder = new StringBuilder();
				passwordGuarded.access(passwordBuilder::append);
				String password = passwordBuilder.toString();

				String auth = username + ":" + password;
				String encoded = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
				request.setHeader("Authorization", "Basic " + encoded);

				LOG.ok("AUTH: Usando Basic Auth para usuario {0} - URI: {1}", username, request.getURI());
				return;
			} else {
				LOG.warn("AUTH: authMethod BASIC, pero usuario o contraseña no están configurados - URI: {0}", request.getURI());
			}
		}

		// ======== Sin autenticación ========
		if ("NONE".equalsIgnoreCase(config.getAuthMethod()) || StringUtil.isBlank(config.getAuthMethod())) {
			LOG.ok("AUTH: Método de autenticación configurado como NONE o vacío. Enviando solicitud sin encabezado de autenticación. URI: {0}", request.getURI());
			return;
		}

		// ======== Configuración inválida ========
		LOG.warn("AUTH: No se pudo determinar un método de autenticación válido. Solicitud será anónima. URI: {0}", request.getURI());
	}


	@Override
	public Uid create(ObjectClass objectClass, Set<Attribute> attributes, OperationOptions operationOptions) {
		String endpointSuffix; ObjectClassInfo oci; Map<String, AttributeMetadata> metadataMap; Set<String> managedNames;
		if (ObjectClass.ACCOUNT.is(objectClass.getObjectClassValue())) {
			endpointSuffix = PATRONS_ENDPOINT_SUFFIX; oci = schema().findObjectClassInfo(ObjectClass.ACCOUNT_NAME);
			metadataMap = KOHA_PATRON_ATTRIBUTE_METADATA; managedNames = MANAGED_KOHA_PATRON_CONNID_NAMES;
		} else if (ObjectClass.GROUP.is(objectClass.getObjectClassValue())) {
			endpointSuffix = CATEGORIES_ENDPOINT_SUFFIX; oci = schema().findObjectClassInfo(ObjectClass.GROUP_NAME);
			metadataMap = KOHA_CATEGORY_ATTRIBUTE_METADATA; managedNames = MANAGED_KOHA_CATEGORY_CONNID_NAMES;
		} else { throw new UnsupportedOperationException("Create operation unsupported for: " + objectClass); }
		JSONObject kohaJsonPayload = buildJsonForKoha(attributes, oci, metadataMap, managedNames, true);
		LOG.info("CREATE: Payload for Koha ({0}): {1}", oci.getType(), kohaJsonPayload.toString());
		String endpoint = getConfiguration().getServiceAddress() + API_BASE_PATH + endpointSuffix;
		HttpPost request = new HttpPost(endpoint);
		JSONObject responseJson = callRequest(request, kohaJsonPayload);
		String newNativeUid = ObjectClass.ACCOUNT.is(objectClass.getObjectClassValue()) ? responseJson.optString(KOHA_PATRON_ID_NATIVE_NAME) : responseJson.optString(KOHA_CATEGORY_ID_NATIVE_NAME);
		if (StringUtil.isBlank(newNativeUid)) { throw new ConnectorException("Koha response for CREATE did not contain native UID. Response: " + responseJson.toString());}
		LOG.ok("CREATE: Koha object ({0}) created successfully. Native UID={1}", oci.getType(), newNativeUid);
		return new Uid(newNativeUid);
	}

	@Override
	public Uid update(ObjectClass objectClass, Uid uid, Set<Attribute> attributes, OperationOptions operationOptions) {
		String endpointSuffix; ObjectClassInfo oci; Map<String, AttributeMetadata> metadataMap; Set<String> managedNames;
		if (ObjectClass.ACCOUNT.is(objectClass.getObjectClassValue())) {
			endpointSuffix = PATRONS_ENDPOINT_SUFFIX; oci = schema().findObjectClassInfo(ObjectClass.ACCOUNT_NAME);
			metadataMap = KOHA_PATRON_ATTRIBUTE_METADATA; managedNames = MANAGED_KOHA_PATRON_CONNID_NAMES;
		} else if (ObjectClass.GROUP.is(objectClass.getObjectClassValue())) {
			endpointSuffix = CATEGORIES_ENDPOINT_SUFFIX; oci = schema().findObjectClassInfo(ObjectClass.GROUP_NAME);
			metadataMap = KOHA_CATEGORY_ATTRIBUTE_METADATA; managedNames = MANAGED_KOHA_CATEGORY_CONNID_NAMES;
		} else { throw new UnsupportedOperationException("Update operation unsupported for: " + objectClass); }
		if (attributes == null || attributes.isEmpty()) { LOG.info("UPDATE: No attributes to update for UID={0}", uid.getUidValue()); return uid; }
		JSONObject kohaJsonPayload = buildJsonForKoha(attributes, oci, metadataMap, managedNames, false);
		LOG.info("UPDATE: Payload for Koha ({0}): {1}", oci.getType(), kohaJsonPayload.toString());
		String endpoint = getConfiguration().getServiceAddress() + API_BASE_PATH + endpointSuffix + "/" + uid.getUidValue();
		HttpPut request = new HttpPut(endpoint);
		callRequest(request, kohaJsonPayload);
		LOG.ok("UPDATE: Koha object ({0}) updated successfully. UID={1}", oci.getType(), uid.getUidValue());
		return uid;
	}

	private JSONObject buildJsonForKoha(Set<Attribute> attributes, ObjectClassInfo oci,
										Map<String, AttributeMetadata> metadataMap,
										Set<String> managedConnIdNames, boolean isCreate) {
		JSONObject jo = new JSONObject(); Set<String> processedKohaAttrs = new HashSet<>();
		for (Attribute attr : attributes) {
			String connIdAttrName = attr.getName(); List<Object> values = attr.getValue();
			if (Uid.NAME.equals(connIdAttrName)) continue;
			AttributeMetadata meta = metadataMap.get(connIdAttrName);
			if (Name.NAME.equals(connIdAttrName)) {
				meta = ObjectClass.ACCOUNT_NAME.equals(oci.getType()) ? metadataMap.get(ATTR_USERID) : metadataMap.get(ATTR_CATEGORY_DESCRIPTION);
			}
			if (meta == null) { LOG.warn("BUILD_JSON: Metadata for ConnId Attr '{0}' not found. Skipping.", connIdAttrName); continue; }
			String kohaAttrName = meta.kohaNativeName;
			if (!managedConnIdNames.contains(meta.connIdName)) { LOG.info("BUILD_JSON: ConnId Attr '{0}' (Koha: '{1}') not in managed set. Skipping.", meta.connIdName, kohaAttrName); continue; }
			if (isCreate && meta.flags.contains(AttributeMetadata.Flags.NOT_CREATABLE)) { LOG.info("BUILD_JSON: Attr '{0}' not creatable. Skipping.", meta.connIdName); continue; }
			if (!isCreate && meta.flags.contains(AttributeMetadata.Flags.NOT_UPDATEABLE)) { LOG.info("BUILD_JSON: Attr '{0}' not updateable. Skipping.", meta.connIdName); continue; }
			if (values == null || values.isEmpty() || values.get(0) == null) { if (!isCreate) jo.put(kohaAttrName, JSONObject.NULL); continue; }
			if (processedKohaAttrs.contains(kohaAttrName)) continue;
			Object kohaValue;
			if (meta.flags.contains(AttributeMetadata.Flags.MULTIVALUED)) {
				JSONArray ja = new JSONArray();
				for (Object val : values) {
					ja.put(convertConnIdValueToKohaJsonValue(val, meta.type, meta.connIdName));
				}
				kohaValue = ja;
			} else {
				kohaValue = convertConnIdValueToKohaJsonValue(values.get(0), meta.type, meta.connIdName);
			}
			jo.put(kohaAttrName, kohaValue); processedKohaAttrs.add(kohaAttrName);
		}
		return jo;
	}

	private Object convertConnIdValueToKohaJsonValue(Object connIdValue, Class<?> connIdType, String connIdAttrName) {
		if (connIdValue == null) return JSONObject.NULL;
		if (connIdType.equals(String.class)) {
			AttributeMetadata meta = KOHA_PATRON_ATTRIBUTE_METADATA.get(connIdAttrName);
			if (meta == null) meta = KOHA_CATEGORY_ATTRIBUTE_METADATA.get(connIdAttrName);
			if (meta != null && meta.kohaNativeName != null && (meta.kohaNativeName.equals(ATTR_DATE_OF_BIRTH) || meta.kohaNativeName.equals(ATTR_EXPIRY_DATE) ||
					meta.kohaNativeName.equals(ATTR_DATE_ENROLLED) || meta.kohaNativeName.equals(ATTR_DATE_RENEWED))) {
				try { LocalDate.parse(connIdValue.toString(), KOHA_DATE_FORMATTER); return connIdValue.toString(); }
				catch (DateTimeParseException e) { LOG.error("Invalid date format for {0}: {1}. Expected yyyy-MM-dd.", connIdAttrName, connIdValue); throw new InvalidAttributeValueException("Invalid date format for " + connIdAttrName + ": " + connIdValue, e); }
			}
			return connIdValue.toString();
		} else if (connIdType.equals(Boolean.class) || connIdType.equals(Integer.class) || connIdType.equals(Long.class)) {
			return connIdValue;
		}
		return connIdValue.toString();
	}

	@Override
	public void delete(ObjectClass objectClass, Uid uid, OperationOptions operationOptions) {
		String endpointSuffix = ObjectClass.ACCOUNT.is(objectClass.getObjectClassValue()) ? PATRONS_ENDPOINT_SUFFIX :
				(ObjectClass.GROUP.is(objectClass.getObjectClassValue()) ? CATEGORIES_ENDPOINT_SUFFIX : null);
		if (endpointSuffix == null) throw new UnsupportedOperationException("Delete unsupported for: " + objectClass);
		String endpoint = getConfiguration().getServiceAddress() + API_BASE_PATH + endpointSuffix + "/" + uid.getUidValue();
		HttpDelete request = new HttpDelete(endpoint);
		try { callRequest(request); LOG.ok("DELETE: UID={0} from {1} successful.", uid.getUidValue(), endpointSuffix); }
		catch (IOException e) { LOG.error("DELETE: Error for UID={0}. Msg: {1}", uid.getUidValue(),e.getMessage()); throw new ConnectorIOException("DELETE HTTP call failed: " + e.getMessage(), e); }
	}

	@Override
	public FilterTranslator<RestUsersFilter> createFilterTranslator(ObjectClass objectClass, OperationOptions operationOptions) {
		return new RestUsersFilterTranslator();
	}

	@Override
	public void executeQuery(ObjectClass objectClass, RestUsersFilter filter, ResultsHandler handler, OperationOptions options) {
		ObjectClassInfo oci = schema().findObjectClassInfo(objectClass.getObjectClassValue());
		if (oci == null) throw new ConnectorException("Unsupported object class for query: " + objectClass);
		String baseEndpoint; Map<String, AttributeMetadata> metadataMap;
		if (ObjectClass.ACCOUNT.is(objectClass.getObjectClassValue())) {
			baseEndpoint = getConfiguration().getServiceAddress() + API_BASE_PATH + PATRONS_ENDPOINT_SUFFIX;
			metadataMap = KOHA_PATRON_ATTRIBUTE_METADATA;
		} else if (ObjectClass.GROUP.is(objectClass.getObjectClassValue())) {
			baseEndpoint = getConfiguration().getServiceAddress() + API_BASE_PATH + CATEGORIES_ENDPOINT_SUFFIX;
			metadataMap = KOHA_CATEGORY_ATTRIBUTE_METADATA;
		} else { LOG.warn("EXECUTE_QUERY: Unsupported ObjectClass {0}", objectClass); return; }
		try {
			int pageSize = options.getPageSize() != null ? options.getPageSize() : 100;
			int currentPageForApi = 1;
			// No usamos PagedResultsCookie directamente si no podemos importarlo.
			// La paginación se hará obteniendo todas las páginas en un bucle.

			if (filter != null && StringUtil.isNotBlank(filter.getByUid())) {
				HttpGet request = new HttpGet(baseEndpoint + "/" + filter.getByUid());
				JSONObject itemJson = new JSONObject(callRequest(request));
				ConnectorObject co = convertKohaJsonToConnectorObject(itemJson, oci, metadataMap);
				if (co != null) handler.handle(co);
				return;
			}

			List<String> baseQueryParamsList = new ArrayList<>();
			baseQueryParamsList.add("_per_page=" + pageSize);

			String filterQueryString = null;
			if (filter != null) {
				String kohaFieldNameForName = ObjectClass.ACCOUNT.is(objectClass.getObjectClassValue()) ?
						KOHA_PATRON_ATTRIBUTE_METADATA.get(ATTR_USERID).kohaNativeName :
						KOHA_CATEGORY_ATTRIBUTE_METADATA.get(ATTR_CATEGORY_DESCRIPTION).kohaNativeName;
				String kohaFieldNameForEmail = ObjectClass.ACCOUNT.is(objectClass.getObjectClassValue()) && KOHA_PATRON_ATTRIBUTE_METADATA.containsKey(ATTR_EMAIL) ?
						KOHA_PATRON_ATTRIBUTE_METADATA.get(ATTR_EMAIL).kohaNativeName : null;
				List<String> qFilters = new ArrayList<>();
				if (StringUtil.isNotBlank(filter.getByName())) {
					qFilters.add(kohaFieldNameForName + ":" + filter.getByName()); // ¡CONFIRMAR SINTAXIS!
				}
				if (StringUtil.isNotBlank(filter.getByEmail()) && kohaFieldNameForEmail != null) {
					qFilters.add(kohaFieldNameForEmail + ":" + filter.getByEmail()); // ¡CONFIRMAR SINTAXIS!
				}
				if (!qFilters.isEmpty()) {
					filterQueryString = String.join(" AND ", qFilters); // ¡CONFIRMAR SINTAXIS 'AND' PARA 'q'!
					baseQueryParamsList.add("_match=exact");
					try { baseQueryParamsList.add("q=" + URLEncoder.encode(filterQueryString, StandardCharsets.UTF_8.name())); }
					catch (UnsupportedEncodingException e) { throw new ConnectorIOException("UTF-8 encoding not supported", e); }
				}
			}

			boolean moreResults;
			String baseQueryPart = String.join("&", baseQueryParamsList);

			do {
				String pageParam = "_page=" + currentPageForApi;
				String finalQueryParams = "?" + (baseQueryPart.isEmpty() ? pageParam : baseQueryPart + "&" + pageParam);

				HttpGet request = new HttpGet(baseEndpoint + finalQueryParams);
				LOG.ok("EXECUTE_QUERY: Requesting URL: {0}", request.getURI());
				String responseStr = callRequest(request);
				JSONArray itemsArray = new JSONArray(responseStr);

				for (int i = 0; i < itemsArray.length(); i++) {
					ConnectorObject co = convertKohaJsonToConnectorObject(itemsArray.getJSONObject(i), oci, metadataMap);
					if (co != null && !handler.handle(co)){
						LOG.ok("EXECUTE_QUERY: Handler stopped processing.");
						return;
					}
				}
				if (itemsArray.length() < pageSize) {
					moreResults = false;
				} else {
					currentPageForApi++;
					moreResults = true;
				}
			} while (moreResults);
		} catch (IOException | JSONException e) { LOG.error("EXECUTE_QUERY: Error. Msg: {0}", e.getMessage(), e); throw new ConnectorIOException("Query failed: " + e.getMessage(), e); }
	}

	private ConnectorObject convertKohaJsonToConnectorObject(JSONObject kohaJson, ObjectClassInfo oci, Map<String, AttributeMetadata> metadataMap) {
		if (kohaJson == null) return null;
		ConnectorObjectBuilder builder = new ConnectorObjectBuilder().setObjectClass(new ObjectClass(oci.getType()));
		String uidValue = null; String nameValue = null;
		for (AttributeInfo attrInfoFromSchema : oci.getAttributeInfo()) {
			String connIdAttrName = attrInfoFromSchema.getName();
			AttributeMetadata meta = Uid.NAME.equals(connIdAttrName) ? new AttributeMetadata(Uid.NAME, ObjectClass.ACCOUNT_NAME.equals(oci.getType()) ? KOHA_PATRON_ID_NATIVE_NAME : KOHA_CATEGORY_ID_NATIVE_NAME, String.class) :
					(Name.NAME.equals(connIdAttrName) ? (ObjectClass.ACCOUNT_NAME.equals(oci.getType()) ? metadataMap.get(ATTR_USERID) : metadataMap.get(ATTR_CATEGORY_DESCRIPTION)) :
							metadataMap.get(connIdAttrName));
			if (meta == null || meta.kohaNativeName == null || (meta.flags != null && meta.flags.contains(AttributeMetadata.Flags.NOT_READABLE)) ||
					!kohaJson.has(meta.kohaNativeName) || kohaJson.isNull(meta.kohaNativeName)) {
				continue;
			}
			Object kohaNativeValue = kohaJson.get(meta.kohaNativeName);
			Object connIdValue = convertKohaValueToConnIdValue(kohaNativeValue, meta.type, meta.connIdName);
			if (connIdValue != null) {
				if (attrInfoFromSchema.isMultiValued()) {
					if (kohaNativeValue instanceof JSONArray) {
						List<Object> multiValues = new ArrayList<>();
						JSONArray kohaArray = (JSONArray) kohaNativeValue;
						for(int i=0; i < kohaArray.length(); i++) {
							Object convertedItem = convertKohaValueToConnIdValue(kohaArray.get(i), meta.type, meta.connIdName);
							if (convertedItem != null) multiValues.add(convertedItem);
						}
						if (!multiValues.isEmpty()) builder.addAttribute(AttributeBuilder.build(meta.connIdName, multiValues));
					} else {
						builder.addAttribute(AttributeBuilder.build(meta.connIdName, Collections.singletonList(connIdValue)));
					}
				} else {
					builder.addAttribute(AttributeBuilder.build(meta.connIdName, connIdValue));
				}
			}
		}
		uidValue = ObjectClass.ACCOUNT_NAME.equals(oci.getType()) ? kohaJson.optString(KOHA_PATRON_ID_NATIVE_NAME) : kohaJson.optString(KOHA_CATEGORY_ID_NATIVE_NAME);
		AttributeMetadata nameMetaVal = ObjectClass.ACCOUNT_NAME.equals(oci.getType()) ? metadataMap.get(ATTR_USERID) : metadataMap.get(ATTR_CATEGORY_DESCRIPTION);
		if (nameMetaVal != null) nameValue = kohaJson.optString(nameMetaVal.kohaNativeName);
		if (StringUtil.isBlank(uidValue)) { LOG.error("CONVERT_JSON: No UID for Koha object: {0}", kohaJson.toString()); return null; }
		builder.setUid(uidValue);
		builder.setName(StringUtil.isNotBlank(nameValue) ? nameValue : uidValue);
		return builder.build();
	}

	private Object convertKohaValueToConnIdValue(Object kohaValue, Class<?> connIdType, String connIdAttrName) {
		if (kohaValue == null || JSONObject.NULL.equals(kohaValue)) return null;
		try {
			if (connIdType.equals(String.class)) {
				AttributeMetadata meta = KOHA_PATRON_ATTRIBUTE_METADATA.get(connIdAttrName);
				if (meta == null) meta = KOHA_CATEGORY_ATTRIBUTE_METADATA.get(connIdAttrName);
				if (meta != null && meta.kohaNativeName != null && (meta.kohaNativeName.equals(ATTR_DATE_OF_BIRTH) || meta.kohaNativeName.equals(ATTR_EXPIRY_DATE) ||
						meta.kohaNativeName.equals(ATTR_DATE_ENROLLED) || meta.kohaNativeName.equals(ATTR_DATE_RENEWED))) {
					return LocalDate.parse(kohaValue.toString(), KOHA_DATE_FORMATTER).toString();
				} else if (meta != null && meta.kohaNativeName != null && (meta.kohaNativeName.equals(ATTR_UPDATED_ON) || meta.kohaNativeName.equals(ATTR_LAST_SEEN))) {
					return ZonedDateTime.parse(kohaValue.toString(), KOHA_DATETIME_FORMATTER).toString();
				}
				return kohaValue.toString();
			} else if (connIdType.equals(Boolean.class)) {
				return kohaValue instanceof Boolean ? kohaValue : ("true".equalsIgnoreCase(kohaValue.toString()) || "1".equals(kohaValue.toString()));
			} else if (connIdType.equals(Integer.class)) {
				return kohaValue instanceof Integer ? kohaValue : Integer.parseInt(kohaValue.toString());
			} else if (connIdType.equals(Long.class)) {
				return kohaValue instanceof Long ? kohaValue : Long.parseLong(kohaValue.toString());
			}
		} catch (NumberFormatException | DateTimeParseException e) { LOG.warn("CONVERT_KOHA_VALUE: Parse error for '{0}' (attr: {1}) to type '{2}'. Error: {3}",kohaValue, connIdAttrName, connIdType.getSimpleName(), e.getMessage()); return null; }
		return kohaValue.toString();
	}

	private JSONObject callRequest(HttpEntityEnclosingRequestBase request, JSONObject payload) {
		LOG.ok("HTTP_CALL_ENTITY: URI={0}", request.getURI());
		request.setHeader("Content-Type", "application/json"); request.setHeader("Accept", "application/json");
		try { addAuthHeader(request); } catch (IOException e) { throw new ConnectorIOException("Auth header setup failed", e); }
		if (payload != null) request.setEntity(new ByteArrayEntity(StringUtils.getBytesUtf8(payload.toString())));
		try (CloseableHttpResponse response = execute(request)) {
			processResponseErrors(response); String result = EntityUtils.toString(response.getEntity());
			LOG.ok("HTTP_CALL_ENTITY: Response Body={0}", result);
			return StringUtil.isBlank(result) ? new JSONObject() : new JSONObject(result);
		} catch (IOException e) { throw new ConnectorIOException("HTTP call (with entity) failed: " + e.getMessage(), e); }
		catch (JSONException e) { throw new ConnectorException("JSON parsing of response (with entity) failed: " + e.getMessage(), e); }
	}

	private String callRequest(HttpRequestBase request) throws IOException {
		LOG.ok("HTTP_CALL_NO_ENTITY: URI={0}", request.getURI());
		request.setHeader("Accept", "application/json"); addAuthHeader(request);
		try (CloseableHttpResponse response = execute(request)) {
			processResponseErrors(response); String result = EntityUtils.toString(response.getEntity());
			LOG.ok("HTTP_CALL_NO_ENTITY: Response Body={0}", result); return result;
		}
	}

	@Override
	public void processResponseErrors(CloseableHttpResponse response) throws ConnectorIOException {
		int statusCode = response.getStatusLine().getStatusCode(); if (statusCode >= 200 && statusCode < 300) return;
		String responseBody = null; HttpEntity entity = response.getEntity();
		if (entity != null) { try { responseBody = EntityUtils.toString(entity); } catch (IOException e) { LOG.warn("Cannot read error response body for {0}: {1}", statusCode, e.getMessage()); }}
		String message = "HTTP error " + statusCode + " " + response.getStatusLine().getReasonPhrase() + (responseBody != null ? " : " + responseBody : "");
		LOG.error("HTTP_ERROR: {0}", message);
		try { response.close(); } catch (IOException e) { LOG.warn("Failed to close error response: {0}", e.getMessage());}
		switch (statusCode) {
			case 400: throw new InvalidAttributeValueException(message); case 401: case 403: throw new PermissionDeniedException(message);
			case 404: throw new UnknownUidException(message);            case 408: throw new OperationTimeoutException(message);
			case 409: throw new AlreadyExistsException(message);         case 412: throw new PreconditionFailedException(message);
			default: throw new ConnectorIOException(message);
		}
	}

	@Override
	public void test() {
		LOG.info("TEST: Starting Koha connection test...");
		String testEndpoint = getConfiguration().getServiceAddress() + API_BASE_PATH + PATRONS_ENDPOINT_SUFFIX + "?_per_page=1";
		HttpGet request = new HttpGet(testEndpoint);
		try {
			callRequest(request);
			LOG.info("TEST: Connection test successful to {0}", testEndpoint);
		} catch (Exception e) {
			LOG.error("TEST: Connection test failed. Endpoint: {0}, Error: {1}", testEndpoint, e.getMessage(), e);
			if (e instanceof ConnectorException) throw (ConnectorException) e;
			throw new ConnectorIOException("Connection test failed: " + e.getMessage(), e);
		}
	}
}
