package com.identicum.connectors;

import com.evolveum.polygon.rest.AbstractRestConnector;
import org.apache.commons.codec.binary.StringUtils;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.identityconnectors.common.StringUtil;
import org.identityconnectors.common.logging.Log;
import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.common.exceptions.*; // Import general para excepciones de ConnId
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
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

@ConnectorClass(displayNameKey = "connector.identicum.rest.display", configurationClass = RestUsersConfiguration.class)
public class RestUsersConnector
		extends AbstractRestConnector<RestUsersConfiguration>
		implements CreateOp, UpdateOp, SchemaOp, SearchOp<RestUsersFilter>, DeleteOp, TestOp {

	private static final Log LOG = Log.getLog(RestUsersConnector.class);

	// --- OAuth2 Token Management ---
	private volatile String oauthAccessToken;
	private volatile long oauthTokenExpiryEpoch = 0L;
	private static final Object tokenLock = new Object();
	private static final int TOKEN_EXPIRY_BUFFER_SECONDS = 60;

	// --- API Path Constants ---
	// CORREGIDO según la aclaración del usuario:
	// Si serviceAddress es http://192.168.15.135:8000
	// y el endpoint de patrons es http://192.168.15.135:8000/api/v1/patrons
	// entonces API_BASE_PATH debe ser "/api/v1"
	private static final String API_BASE_PATH = "/api/v1";
	private static final String PATRONS_ENDPOINT_SUFFIX = "/patrons";
	private static final String CATEGORIES_ENDPOINT_SUFFIX = "/patron_categories";

	// --- Native Attribute Names ---
	public static final String KOHA_PATRON_ID_NATIVE_NAME = "patron_id";
	public static final String KOHA_CATEGORY_ID_NATIVE_NAME = "patron_category_id";

	// --- ConnId Attribute Names ---
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
	public static final String ATTR_CATEGORY_DESCRIPTION = "description";
	public static final String ATTR_CATEGORY_TYPE = "category_type";
	public static final String ATTR_CATEGORY_ENROLMENT_PERIOD = "enrolment_period";

	private static final DateTimeFormatter KOHA_DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;
	private static final DateTimeFormatter KOHA_DATETIME_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

	private volatile Schema connectorSchema = null;

	private static class AttributeMetadata {
		final String connIdName; final String kohaNativeName; final Class<?> type; final Set<Flags> flags;
		enum Flags {REQUIRED, NOT_CREATABLE, NOT_UPDATEABLE, NOT_READABLE, MULTIVALUED}
		AttributeMetadata(String connIdName, String kohaNativeName, Class<?> type, Flags... flags) {
			this.connIdName = connIdName; this.kohaNativeName = kohaNativeName; this.type = type;
			this.flags = flags == null ? Collections.emptySet() : new HashSet<>(Arrays.asList(flags));
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
	private static final Set<String> MANAGED_KOHA_PATRON_CONNID_NAMES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
			ATTR_USERID, ATTR_CARDNUMBER, ATTR_SURNAME, ATTR_FIRSTNAME, ATTR_EMAIL, ATTR_PHONE, ATTR_MOBILE,
			ATTR_LIBRARY_ID, ATTR_CATEGORY_ID, ATTR_DATE_OF_BIRTH, ATTR_EXPIRY_DATE,
			ATTR_GENDER, ATTR_ADDRESS, ATTR_ADDRESS2, ATTR_CITY, ATTR_STATE, ATTR_POSTAL_CODE, ATTR_COUNTRY,
			ATTR_STAFF_NOTES, ATTR_OPAC_NOTES,
			ATTR_INCORRECT_ADDRESS, ATTR_PATRON_CARD_LOST, ATTR_RESTRICTED, ATTR_AUTORENEW_CHECKOUTS,
			ATTR_PROTECTED
	)));
	private static final Map<String, AttributeMetadata> KOHA_CATEGORY_ATTRIBUTE_METADATA = new HashMap<>();
	static {
		KOHA_CATEGORY_ATTRIBUTE_METADATA.put(ATTR_CATEGORY_DESCRIPTION, new AttributeMetadata(ATTR_CATEGORY_DESCRIPTION, "name", String.class, AttributeMetadata.Flags.REQUIRED));
		KOHA_CATEGORY_ATTRIBUTE_METADATA.put(ATTR_CATEGORY_TYPE, new AttributeMetadata(ATTR_CATEGORY_TYPE, "category_type", String.class));
		KOHA_CATEGORY_ATTRIBUTE_METADATA.put(ATTR_CATEGORY_ENROLMENT_PERIOD, new AttributeMetadata(ATTR_CATEGORY_ENROLMENT_PERIOD, "enrolment_period", String.class));
	}
	private static final Set<String> MANAGED_KOHA_CATEGORY_CONNID_NAMES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
			ATTR_CATEGORY_DESCRIPTION, ATTR_CATEGORY_TYPE, ATTR_CATEGORY_ENROLMENT_PERIOD
	)));

	@Override
	public void init(org.identityconnectors.framework.spi.Configuration configuration) {
		super.init(configuration);
		this.oauthAccessToken = null;
		this.oauthTokenExpiryEpoch = 0L;

		RestUsersConfiguration currentConfig = getConfiguration();
		if (currentConfig == null) {
			throw new ConfigurationException("Connector configuration is null. Ensure @ConnectorClass refers to the correct configuration class.");
		}
		LOG.ok("Koha Connector initialized. ServiceAddress: {0}, AuthMethod: {1}",
				currentConfig.getServiceAddress(), currentConfig.getAuthMethod());

		if (StringUtil.isNotBlank(currentConfig.getClientId())) {
			if (StringUtil.isBlank(currentConfig.getServiceAddress())) {
				throw new ConfigurationException("Service Address (URL Base) must be configured for OAuth2.");
			}
			if (currentConfig.getClientSecret() == null) {
				throw new ConfigurationException("Client Secret must be configured for OAuth2.");
			}
		}
	}

	@Override
	public Schema schema() {
		if (this.connectorSchema != null) return this.connectorSchema;
		LOG.ok("Building schema for Koha Connector...");
		SchemaBuilder schemaBuilder = new SchemaBuilder(RestUsersConnector.class);

		// --- INICIO DE LA MODIFICACIÓN ---

		// --- Account Object Class (Patrons) ---
		ObjectClassInfoBuilder accountBuilder = new ObjectClassInfoBuilder();
		accountBuilder.setType(ObjectClass.ACCOUNT_NAME);

		// Crear una copia mutable de los nombres de atributos a procesar.
		Set<String> patronAttrNamesToProcess = new HashSet<>(KOHA_PATRON_ATTRIBUTE_METADATA.keySet());

		// Definir __UID__ (Identificador único)
		accountBuilder.addAttributeInfo(AttributeInfoBuilder.define(Uid.NAME)
				.setNativeName(KOHA_PATRON_ID_NATIVE_NAME).setType(String.class)
				.setRequired(true).setCreateable(false).setUpdateable(false).setReadable(true).build());
		patronAttrNamesToProcess.remove(Uid.NAME); // No es un atributo regular

		// Definir __NAME__ (Identificador legible/login)
		AttributeMetadata accountNameMeta = KOHA_PATRON_ATTRIBUTE_METADATA.get(ATTR_USERID);
		if (accountNameMeta != null) {
			accountBuilder.addAttributeInfo(AttributeInfoBuilder.define(Name.NAME)
					.setNativeName(accountNameMeta.kohaNativeName).setType(accountNameMeta.type)
					.setRequired(accountNameMeta.flags.contains(AttributeMetadata.Flags.REQUIRED))
					.setCreateable(!accountNameMeta.flags.contains(AttributeMetadata.Flags.NOT_CREATABLE))
					.setUpdateable(!accountNameMeta.flags.contains(AttributeMetadata.Flags.NOT_UPDATEABLE))
					.setReadable(!accountNameMeta.flags.contains(AttributeMetadata.Flags.NOT_READABLE)).build());

			// MUY IMPORTANTE: Remover ATTR_USERID del conjunto de atributos a procesar,
			// ya que ahora está representado por __NAME__.
			patronAttrNamesToProcess.remove(ATTR_USERID);
		}

		// Procesar todos los demás atributos de patron
		patronAttrNamesToProcess.forEach(connIdName -> {
			AttributeMetadata meta = KOHA_PATRON_ATTRIBUTE_METADATA.get(connIdName);
			accountBuilder.addAttributeInfo(AttributeInfoBuilder.define(meta.connIdName)
					.setNativeName(meta.kohaNativeName).setType(meta.type)
					.setRequired(meta.flags.contains(AttributeMetadata.Flags.REQUIRED))
					.setMultiValued(meta.flags.contains(AttributeMetadata.Flags.MULTIVALUED))
					.setCreateable(!meta.flags.contains(AttributeMetadata.Flags.NOT_CREATABLE))
					.setUpdateable(!meta.flags.contains(AttributeMetadata.Flags.NOT_UPDATEABLE))
					.setReadable(!meta.flags.contains(AttributeMetadata.Flags.NOT_READABLE)).build());
		});
		schemaBuilder.defineObjectClass(accountBuilder.build());


		// --- Group Object Class (Categories) ---
		ObjectClassInfoBuilder groupBuilder = new ObjectClassInfoBuilder();
		groupBuilder.setType(ObjectClass.GROUP_NAME);
		Set<String> categoryAttrNamesToProcess = new HashSet<>(KOHA_CATEGORY_ATTRIBUTE_METADATA.keySet());

		groupBuilder.addAttributeInfo(AttributeInfoBuilder.define(Uid.NAME)
				.setNativeName(KOHA_CATEGORY_ID_NATIVE_NAME).setType(String.class)
				.setRequired(true).setCreateable(false).setUpdateable(false).setReadable(true).build());
		categoryAttrNamesToProcess.remove(Uid.NAME);

		AttributeMetadata groupNameMeta = KOHA_CATEGORY_ATTRIBUTE_METADATA.get(ATTR_CATEGORY_DESCRIPTION);
		if (groupNameMeta != null) {
			groupBuilder.addAttributeInfo(AttributeInfoBuilder.define(Name.NAME)
					.setNativeName(groupNameMeta.kohaNativeName).setType(groupNameMeta.type)
					.setRequired(groupNameMeta.flags.contains(AttributeMetadata.Flags.REQUIRED))
					.setCreateable(!groupNameMeta.flags.contains(AttributeMetadata.Flags.NOT_CREATABLE))
					.setUpdateable(!groupNameMeta.flags.contains(AttributeMetadata.Flags.NOT_UPDATEABLE))
					.setReadable(!groupNameMeta.flags.contains(AttributeMetadata.Flags.NOT_READABLE)).build());
			categoryAttrNamesToProcess.remove(ATTR_CATEGORY_DESCRIPTION);
		}

		categoryAttrNamesToProcess.forEach(connIdName -> {
			AttributeMetadata meta = KOHA_CATEGORY_ATTRIBUTE_METADATA.get(connIdName);
			groupBuilder.addAttributeInfo(AttributeInfoBuilder.define(meta.connIdName)
					.setNativeName(meta.kohaNativeName).setType(meta.type)
					.setRequired(meta.flags.contains(AttributeMetadata.Flags.REQUIRED))
					.setMultiValued(meta.flags.contains(AttributeMetadata.Flags.MULTIVALUED))
					.setCreateable(!meta.flags.contains(AttributeMetadata.Flags.NOT_CREATABLE))
					.setUpdateable(!meta.flags.contains(AttributeMetadata.Flags.NOT_UPDATEABLE))
					.setReadable(!meta.flags.contains(AttributeMetadata.Flags.NOT_READABLE)).build());
		});
		schemaBuilder.defineObjectClass(groupBuilder.build());

		// --- FIN DE LA MODIFICACIÓN ---

		this.connectorSchema = schemaBuilder.build();
		LOG.ok("Schema built successfully.");
		return this.connectorSchema;
	}

	private void addAuthHeader(HttpRequestBase request) throws IOException {
		RestUsersConfiguration config = getConfiguration();
		String authMethod = config.getAuthMethod();
		boolean useOAuth2 = StringUtil.isNotBlank(config.getClientId()) && config.getClientSecret() != null;

		if (useOAuth2 && !"BASIC".equalsIgnoreCase(authMethod)) {
			LOG.ok("AUTH: Attempting OAuth2 for URI: {0}", request.getURI());
			try {
				request.setHeader("Authorization", "Bearer " + getValidOAuthToken());
				return;
			} catch (ConfigurationException | ConnectorIOException e) {
				LOG.error("AUTH: OAuth2 failed for URI: {0}. Error: {1}", request.getURI(), e.getMessage(), e);
				throw e;
			}
		}
		if ("BASIC".equalsIgnoreCase(authMethod)) {
			String username = config.getUsername();
			GuardedString passwordGuarded = config.getPassword();
			if (StringUtil.isNotBlank(username) && passwordGuarded != null) {
				final StringBuilder passBuilder = new StringBuilder();
				passwordGuarded.access(passBuilder::append);
				String auth = username + ":" + passBuilder.toString();
				request.setHeader("Authorization", "Basic " + Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8)));
				LOG.ok("AUTH: Added Basic Auth for user {0}, URI: {1}", username, request.getURI());
				return;
			} else {
				LOG.warn("AUTH: authMethod=BASIC but username/password missing. Anonymous request to URI: {0}", request.getURI());
			}
		}
		LOG.ok("AUTH: No specific auth (OAuth2/Basic) or authMethod=NONE. Anonymous request to URI: {0}", request.getURI());
	}

	@Override
	public Uid create(ObjectClass oClass, Set<Attribute> attrs, OperationOptions opts) {
		String endpointSuffix; ObjectClassInfo oci; Map<String, AttributeMetadata> metaMap; Set<String> managedNames;
		if (ObjectClass.ACCOUNT.is(oClass.getObjectClassValue())) {
			endpointSuffix = PATRONS_ENDPOINT_SUFFIX; oci = schema().findObjectClassInfo(ObjectClass.ACCOUNT_NAME);
			metaMap = KOHA_PATRON_ATTRIBUTE_METADATA; managedNames = MANAGED_KOHA_PATRON_CONNID_NAMES;
		} else if (ObjectClass.GROUP.is(oClass.getObjectClassValue())) {
			endpointSuffix = CATEGORIES_ENDPOINT_SUFFIX; oci = schema().findObjectClassInfo(ObjectClass.GROUP_NAME);
			metaMap = KOHA_CATEGORY_ATTRIBUTE_METADATA; managedNames = MANAGED_KOHA_CATEGORY_CONNID_NAMES;
		} else {
			throw new UnsupportedOperationException("Create unsupported for: " + oClass.getObjectClassValue());
		}
		JSONObject payload = buildJsonForKoha(attrs, oci, metaMap, managedNames, true);
		LOG.info("CREATE: Payload for Koha ({0}): {1}", oci.getType(), payload.toString());
		String endpoint = getConfiguration().getServiceAddress() + API_BASE_PATH + endpointSuffix;
		HttpPost request = new HttpPost(endpoint);
		JSONObject responseJson = callRequest(request, payload);
		String newUid = ObjectClass.ACCOUNT.is(oClass.getObjectClassValue()) ?
				responseJson.optString(KOHA_PATRON_ID_NATIVE_NAME) : responseJson.optString(KOHA_CATEGORY_ID_NATIVE_NAME);
		if (StringUtil.isBlank(newUid)) {
			throw new ConnectorException("Koha CREATE response missing native UID. Body: " + responseJson.toString());
		}
		LOG.ok("CREATE: Koha object ({0}) created. UID={1}", oci.getType(), newUid);
		return new Uid(newUid);
	}

	@Override
	public Uid update(ObjectClass oClass, Uid uid, Set<Attribute> attrs, OperationOptions opts) {
		String endpointSuffix; ObjectClassInfo oci; Map<String, AttributeMetadata> metaMap; Set<String> managedNames;
		if (ObjectClass.ACCOUNT.is(oClass.getObjectClassValue())) {
			endpointSuffix = PATRONS_ENDPOINT_SUFFIX; oci = schema().findObjectClassInfo(ObjectClass.ACCOUNT_NAME);
			metaMap = KOHA_PATRON_ATTRIBUTE_METADATA; managedNames = MANAGED_KOHA_PATRON_CONNID_NAMES;
		} else if (ObjectClass.GROUP.is(oClass.getObjectClassValue())) {
			endpointSuffix = CATEGORIES_ENDPOINT_SUFFIX; oci = schema().findObjectClassInfo(ObjectClass.GROUP_NAME);
			metaMap = KOHA_CATEGORY_ATTRIBUTE_METADATA; managedNames = MANAGED_KOHA_CATEGORY_CONNID_NAMES;
		} else {
			throw new UnsupportedOperationException("Update unsupported for: " + oClass.getObjectClassValue());
		}
		if (attrs == null || attrs.isEmpty()) {
			LOG.info("UPDATE: No attributes for UID={0}. Returning UID.", uid.getUidValue());
			return uid;
		}
		JSONObject payload = buildJsonForKoha(attrs, oci, metaMap, managedNames, false);
		LOG.info("UPDATE: Payload for Koha ({0}): {1}", oci.getType(), payload.toString());
		String endpoint = getConfiguration().getServiceAddress() + API_BASE_PATH + endpointSuffix + "/" + uid.getUidValue();
		HttpPut request = new HttpPut(endpoint);
		callRequest(request, payload);
		LOG.ok("UPDATE: Koha object ({0}) updated. UID={1}", oci.getType(), uid.getUidValue());
		return uid;
	}

	private JSONObject buildJsonForKoha(Set<Attribute> attributes, ObjectClassInfo oci,
										Map<String, AttributeMetadata> metadataMap,
										Set<String> managedConnIdNames, boolean isCreate) {
		JSONObject jo = new JSONObject();
		Set<String> processedKohaAttrs = new HashSet<>();
		for (Attribute attr : attributes) {
			String connIdAttrName = attr.getName();
			if (Uid.NAME.equals(connIdAttrName)) continue;
			AttributeMetadata meta = Name.NAME.equals(connIdAttrName) ?
					(ObjectClass.ACCOUNT_NAME.equals(oci.getType()) ? metadataMap.get(ATTR_USERID) : metadataMap.get(ATTR_CATEGORY_DESCRIPTION))
					: metadataMap.get(connIdAttrName);
			if (meta == null) {
				LOG.warn("BUILD_JSON: No metadata for ConnId Attr '{0}'. Skipping.", connIdAttrName); continue;
			}
			String kohaAttrName = meta.kohaNativeName;
			if (!Name.NAME.equals(connIdAttrName) && !managedConnIdNames.contains(meta.connIdName)) continue;
			if (isCreate && meta.flags.contains(AttributeMetadata.Flags.NOT_CREATABLE)) continue;
			if (!isCreate && meta.flags.contains(AttributeMetadata.Flags.NOT_UPDATEABLE)) continue;

			List<Object> values = attr.getValue();
			if (values == null || values.isEmpty() || values.get(0) == null) {
				if (!isCreate) jo.put(kohaAttrName, JSONObject.NULL);
				continue;
			}
			if (processedKohaAttrs.contains(kohaAttrName)) continue;

			Object kohaValue = meta.flags.contains(AttributeMetadata.Flags.MULTIVALUED) ?
					new JSONArray(values.stream().map(v -> convertConnIdValueToKohaJsonValue(v, meta.type, meta.connIdName)).filter(java.util.Objects::nonNull).toArray())
					: convertConnIdValueToKohaJsonValue(values.get(0), meta.type, meta.connIdName);
			jo.put(kohaAttrName, kohaValue);
			processedKohaAttrs.add(kohaAttrName);
		}
		return jo;
	}

	private Object convertConnIdValueToKohaJsonValue(Object connIdValue, Class<?> connIdType, String connIdAttrName) {
		if (connIdValue == null) return JSONObject.NULL;
		if (connIdType.equals(String.class)) {
			AttributeMetadata meta = KOHA_PATRON_ATTRIBUTE_METADATA.get(connIdAttrName);
			if (meta == null) meta = KOHA_CATEGORY_ATTRIBUTE_METADATA.get(connIdAttrName);
			if (meta != null && meta.kohaNativeName != null &&
					(meta.kohaNativeName.equals(ATTR_DATE_OF_BIRTH) || meta.kohaNativeName.equals(ATTR_EXPIRY_DATE) ||
							meta.kohaNativeName.equals(ATTR_DATE_ENROLLED) || meta.kohaNativeName.equals(ATTR_DATE_RENEWED))) {
				try {
					LocalDate.parse(connIdValue.toString(), KOHA_DATE_FORMATTER);
					return connIdValue.toString();
				} catch (DateTimeParseException e) {
					throw new InvalidAttributeValueException("Invalid date " + connIdValue + " for " + connIdAttrName, e);
				}
			}
			return connIdValue.toString();
		} else if (connIdType.equals(Boolean.class) || connIdType.equals(Integer.class) || connIdType.equals(Long.class)) {
			return connIdValue;
		}
		return connIdValue.toString();
	}

	@Override
	public void delete(ObjectClass oClass, Uid uid, OperationOptions opts) {
		String endpointSuffix = ObjectClass.ACCOUNT.is(oClass.getObjectClassValue()) ? PATRONS_ENDPOINT_SUFFIX :
				(ObjectClass.GROUP.is(oClass.getObjectClassValue()) ? CATEGORIES_ENDPOINT_SUFFIX : null);
		if (endpointSuffix == null) throw new UnsupportedOperationException("Delete unsupported for: " + oClass);
		String endpoint = getConfiguration().getServiceAddress() + API_BASE_PATH + endpointSuffix + "/" + uid.getUidValue();
		HttpDelete request = new HttpDelete(endpoint);
		try {
			callRequest(request);
			LOG.ok("DELETE: UID={0} from {1} successful.", uid.getUidValue(), endpointSuffix);
		} catch (IOException e) {
			throw new ConnectorIOException("DELETE HTTP call failed: " + e.getMessage(), e);
		}
	}

	@Override
	public FilterTranslator<RestUsersFilter> createFilterTranslator(ObjectClass oClass, OperationOptions opts) {
		return new RestUsersFilterTranslator();
	}

	private String urlEncodeUTF8(String s) {
		try {
			return URLEncoder.encode(s, StandardCharsets.UTF_8.name());
		} catch (UnsupportedEncodingException e) {
			throw new ConnectorException("UTF-8 encoding not supported, this should not happen.", e);
		}
	}
	/**
	 * Ejecuta una consulta paginada contra la API de Koha para recuperar objetos ACCOUNT (usuarios) o GROUP (categorías).
	 * Soporta filtros por UID, nombre y correo. Maneja respuestas JSON tanto en array raíz como envueltas en objeto.
	 */
	@Override
	public void executeQuery(ObjectClass oClass, RestUsersFilter filter, ResultsHandler handler, OperationOptions opts) {
		LOG.ok("EXECUTE_QUERY: ObjectClass={0}, Filter={1}, Options={2}", oClass, filter, opts);
		ObjectClassInfo oci = schema().findObjectClassInfo(oClass.getObjectClassValue());
		if (oci == null) throw new ConnectorException("Unsupported query for: " + oClass);

		String baseApiEp, fullBaseUrl;
		Map<String, AttributeMetadata> metaMap;

		if (ObjectClass.ACCOUNT.is(oClass.getObjectClassValue())) {
			baseApiEp = API_BASE_PATH + PATRONS_ENDPOINT_SUFFIX;
			metaMap = KOHA_PATRON_ATTRIBUTE_METADATA;
		} else if (ObjectClass.GROUP.is(oClass.getObjectClassValue())) {
			baseApiEp = API_BASE_PATH + CATEGORIES_ENDPOINT_SUFFIX;
			metaMap = KOHA_CATEGORY_ATTRIBUTE_METADATA;
		} else {
			LOG.warn("EXECUTE_QUERY: Unsupported ObjectClass {0}", oClass);
			return;
		}

		fullBaseUrl = getConfiguration().getServiceAddress() + baseApiEp;

		try {
			if (filter != null && StringUtil.isNotBlank(filter.getByUid())) {
				HttpGet request = new HttpGet(fullBaseUrl + "/" + filter.getByUid());
				JSONObject itemJson = new JSONObject(callRequest(request));
				ConnectorObject co = convertKohaJsonToConnectorObject(itemJson, oci, metaMap);
				if (co != null) handler.handle(co);
				return;
			}

			int pageSize = opts.getPageSize() != null ? opts.getPageSize() : 100;
			int currentPage = 1;
			boolean moreResults = true;

			do {
				List<String> queryParams = new ArrayList<>(Arrays.asList("_per_page=" + pageSize, "_page=" + currentPage));
				if (filter != null) {
					if (ObjectClass.ACCOUNT.is(oClass.getObjectClassValue())) {
						if (StringUtil.isNotBlank(filter.getByName())) queryParams.add(metaMap.get(ATTR_USERID).kohaNativeName + "=" + urlEncodeUTF8(filter.getByName()));
						if (StringUtil.isNotBlank(filter.getByEmail())) queryParams.add(metaMap.get(ATTR_EMAIL).kohaNativeName + "=" + urlEncodeUTF8(filter.getByEmail()));
					} else if (ObjectClass.GROUP.is(oClass.getObjectClassValue()) && StringUtil.isNotBlank(filter.getByName())) {
						queryParams.add(metaMap.get(ATTR_CATEGORY_DESCRIPTION).kohaNativeName + "=" + urlEncodeUTF8(filter.getByName()));
					}
				}

				HttpGet request = new HttpGet(fullBaseUrl + "?" + String.join("&", queryParams));
				LOG.ok("EXECUTE_QUERY: URL: {0}", request.getURI());
				String response = callRequest(request);
				JSONArray items = null;

				try {
					JSONObject json = new JSONObject(response);
					if (ObjectClass.ACCOUNT.is(oClass.getObjectClassValue())) {
						items = json.optJSONArray("patrons");
					} else if (ObjectClass.GROUP.is(oClass.getObjectClassValue())) {
						items = json.optJSONArray("patron_categories");
					}
					if (items == null) {
						items = new JSONArray(response); // fallback para array raíz
					}
				} catch (JSONException e) {
					try {
						items = new JSONArray(response);
					} catch (JSONException ex) {
						throw new ConnectorException("Invalid JSON response from Koha: " + response, ex);
					}
				}

				if (items.length() == 0) {
					moreResults = false;
					break;
				}

				for (int i = 0; i < items.length(); i++) {
					ConnectorObject co = convertKohaJsonToConnectorObject(items.getJSONObject(i), oci, metaMap);
					if (co != null && !handler.handle(co)) {
						moreResults = false;
						break;
					}
				}

				moreResults = (items.length() == pageSize);
				if (moreResults) currentPage++;

			} while (moreResults);
		} catch (IOException | JSONException e) {
			throw new ConnectorIOException("Query failed: " + e.getMessage(), e);
		}
	}


	private ConnectorObject convertKohaJsonToConnectorObject(JSONObject kohaJson, ObjectClassInfo oci, Map<String, AttributeMetadata> metadataMap) {
		if (kohaJson == null) return null;
		ConnectorObjectBuilder builder = new ConnectorObjectBuilder().setObjectClass(new ObjectClass(oci.getType()));
		String uidVal = null, nameVal = null;

		for (AttributeInfo attrInfo : oci.getAttributeInfo()) {
			String connIdName = attrInfo.getName();
			AttributeMetadata meta = Uid.NAME.equals(connIdName) ? new AttributeMetadata(Uid.NAME, ObjectClass.ACCOUNT_NAME.equals(oci.getType()) ? KOHA_PATRON_ID_NATIVE_NAME : KOHA_CATEGORY_ID_NATIVE_NAME, String.class) :
					(Name.NAME.equals(connIdName) ? (ObjectClass.ACCOUNT_NAME.equals(oci.getType()) ? metadataMap.get(ATTR_USERID) : metadataMap.get(ATTR_CATEGORY_DESCRIPTION)) : metadataMap.get(connIdName));
			if (meta == null || meta.kohaNativeName == null || (meta.flags != null && meta.flags.contains(AttributeMetadata.Flags.NOT_READABLE)) ||
					!kohaJson.has(meta.kohaNativeName) || kohaJson.isNull(meta.kohaNativeName)) continue;

			Object kohaNativeVal = kohaJson.get(meta.kohaNativeName);
			Object connIdVal = convertKohaValueToConnIdValue(kohaNativeVal, meta.type, meta.connIdName);
			if (connIdVal == null) continue;

			if (attrInfo.isMultiValued()) {
				if (kohaNativeVal instanceof JSONArray) {
					List<Object> multiVals = new ArrayList<>();
					((JSONArray)kohaNativeVal).forEach(item -> {
						Object convItem = convertKohaValueToConnIdValue(item, meta.type, meta.connIdName);
						if (convItem != null) multiVals.add(convItem);
					});
					if (!multiVals.isEmpty()) builder.addAttribute(AttributeBuilder.build(meta.connIdName, multiVals));
				} else {
					builder.addAttribute(AttributeBuilder.build(meta.connIdName, Collections.singletonList(connIdVal)));
				}
			} else {
				builder.addAttribute(AttributeBuilder.build(meta.connIdName, connIdVal));
			}
		}
		uidVal = ObjectClass.ACCOUNT_NAME.equals(oci.getType()) ? kohaJson.optString(KOHA_PATRON_ID_NATIVE_NAME) : kohaJson.optString(KOHA_CATEGORY_ID_NATIVE_NAME);
		AttributeMetadata nameMeta = ObjectClass.ACCOUNT_NAME.equals(oci.getType()) ? metadataMap.get(ATTR_USERID) : metadataMap.get(ATTR_CATEGORY_DESCRIPTION);
		if (nameMeta != null) {
			nameVal = kohaJson.optString(nameMeta.kohaNativeName);
		}
		if (StringUtil.isBlank(uidVal)) {
			LOG.error("CONVERT_JSON: Se encontró un registro sin UID (patron_id). Se omitirá. JSON: {0}", kohaJson.toString());
			return null; // Omite este registro para no causar un error fatal.
		}
		if (StringUtil.isBlank(nameVal)) {
			// Si el nombre está en blanco, usa el UID como fallback para evitar errores.
			LOG.warn("CONVERT_JSON: El registro con UID={0} no tiene un nombre (userid). Usando UID como nombre.", uidVal);
			nameVal = uidVal;
		}
		builder.setUid(new Uid(uidVal));
		builder.setName(new Name(nameVal));
		return builder.build();
	}

	private Object convertKohaValueToConnIdValue(Object kohaValue, Class<?> connIdType, String connIdAttrName) {
		if (kohaValue == null || JSONObject.NULL.equals(kohaValue)) return null;
		try {
			if (connIdType.equals(String.class)) {
				AttributeMetadata meta = KOHA_PATRON_ATTRIBUTE_METADATA.get(connIdAttrName);
				if (meta == null) meta = KOHA_CATEGORY_ATTRIBUTE_METADATA.get(connIdAttrName);
				if (meta != null && meta.kohaNativeName != null &&
						(meta.kohaNativeName.equals(ATTR_DATE_OF_BIRTH) || meta.kohaNativeName.equals(ATTR_EXPIRY_DATE) ||
								meta.kohaNativeName.equals(ATTR_DATE_ENROLLED) || meta.kohaNativeName.equals(ATTR_DATE_RENEWED))) {
					return LocalDate.parse(kohaValue.toString(), KOHA_DATE_FORMATTER).toString();
				} else if (meta != null && meta.kohaNativeName != null &&
						(meta.kohaNativeName.equals(ATTR_UPDATED_ON) || meta.kohaNativeName.equals(ATTR_LAST_SEEN))) {
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
		} catch (NumberFormatException | DateTimeParseException e) {
			LOG.warn("CONVERT_KOHA_VALUE: Parse error for '{0}' (attr: {1}) to '{2}'. Error: {3}", kohaValue, connIdAttrName, connIdType.getSimpleName(), e.getMessage());
			return null;
		}
		return kohaValue.toString();
	}

	private JSONObject callRequest(HttpEntityEnclosingRequestBase request, JSONObject payload) {
		LOG.ok("HTTP_CALL_ENTITY: Method={0}, URI={1}", request.getMethod(), request.getURI());
		request.setHeader("Content-Type", "application/json"); request.setHeader("Accept", "application/json");
		try { addAuthHeader(request); }
		catch (IOException e) { throw new ConnectorIOException("Auth header setup failed: " + e.getMessage(), e); }
		if (payload != null) request.setEntity(new ByteArrayEntity(StringUtils.getBytesUtf8(payload.toString())));
		try (CloseableHttpResponse response = execute(request)) {
			processResponseErrors(response);
			String result = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
			LOG.ok("HTTP_CALL_ENTITY: Response Body for URI {0} : {1}", request.getURI(), result);
			if (response.getStatusLine().getStatusCode() == 204 || StringUtil.isBlank(result)) {
				return new JSONObject();
			}
			return new JSONObject(result);
		} catch (IOException e) {
			throw new ConnectorIOException("HTTP call (with entity) failed for " + request.getURI() + ": " + e.getMessage(), e);
		} catch (JSONException e) {
			throw new ConnectorException("JSON parsing of response (with entity) failed for " + request.getURI() + ": " + e.getMessage(), e);
		}
	}

	private String callRequest(HttpRequestBase request) throws IOException {
		LOG.ok("HTTP_CALL_NO_ENTITY: Method={0}, URI={1}", request.getMethod(), request.getURI());
		request.setHeader("Accept", "application/json");
		request.setHeader("Accept-Encoding", "gzip"); // ✅ asegúrate de agregar esto

		addAuthHeader(request);

		try (CloseableHttpResponse response = execute(request)) {
			processResponseErrors(response);

			HttpEntity entity = response.getEntity();
			InputStream inputStream = entity.getContent();

			// ✅ Manejar compresión gzip
			Header contentEncoding = entity.getContentEncoding();
			if (contentEncoding != null && "gzip".equalsIgnoreCase(contentEncoding.getValue())) {
				inputStream = new GZIPInputStream(inputStream);
			}

			String result = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
			LOG.ok("HTTP_CALL_NO_ENTITY: Response Body for URI {0} : {1}", request.getURI(), result);
			return result;
		}
	}


	@Override
	public void processResponseErrors(CloseableHttpResponse response) throws ConnectorIOException {
		super.processResponseErrors(response);
	}

	@Override
	public void test() {
		LOG.info("TEST: Starting Koha connection test...");
		String serviceAddress = getConfiguration().getServiceAddress();
		String effectiveApiBasePath = API_BASE_PATH;

		if (serviceAddress.endsWith("/") && effectiveApiBasePath.startsWith("/")) {
			effectiveApiBasePath = effectiveApiBasePath.substring(1);
		} else if (!serviceAddress.endsWith("/") && !effectiveApiBasePath.startsWith("/") && !effectiveApiBasePath.isEmpty()) {
			// No action needed here if API_BASE_PATH always starts with / and serviceAddress does not end with /
		}

		String testEndpoint = serviceAddress + effectiveApiBasePath + PATRONS_ENDPOINT_SUFFIX + "?_per_page=1&_page=1";
		LOG.info("TEST: Constructed test endpoint: {0}", testEndpoint);
		HttpGet request = new HttpGet(testEndpoint);
		try {
			callRequest(request);
			LOG.info("TEST: Connection test successful to: {0}", testEndpoint);
		} catch (Exception e) {
			LOG.error("TEST: Connection test FAILED for: {0}. Error: {1}", testEndpoint, e.getMessage(), e);
			if (e instanceof ConnectorException) throw (ConnectorException) e;
			throw new ConnectorIOException("Connection test failed: " + e.getMessage(), e);
		}
	}
}