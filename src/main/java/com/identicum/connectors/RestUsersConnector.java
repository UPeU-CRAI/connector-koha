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

	private volatile String oauthAccessToken;
	private volatile long oauthTokenExpiryEpoch = 0L;
	private static final Object tokenLock = new Object();
	private static final int TOKEN_EXPIRY_BUFFER_SECONDS = 60;

	private static final String API_BASE_PATH = "/api/v1";
	private static final String PATRONS_ENDPOINT_SUFFIX = "/patrons";
	private static final String CATEGORIES_ENDPOINT_SUFFIX = "/patron_categories";

	public static final String KOHA_PATRON_ID_NATIVE_NAME = "patron_id";
	public static final String KOHA_CATEGORY_ID_NATIVE_NAME = "patron_category_id";

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

	// --- Definiciones para CUENTAS (Patrons) ---
	private static final Map<String, AttributeMetadata> KOHA_PATRON_ATTRIBUTE_METADATA = new HashMap<>();
	private static final Set<String> MANAGED_KOHA_PATRON_CONNID_NAMES;

	static {
		final String ATTR_USERID = "userid";
		final String ATTR_CARDNUMBER = "cardnumber";
		final String ATTR_SURNAME = "surname";
		final String ATTR_FIRSTNAME = "firstname";
		final String ATTR_EMAIL = "email";
		final String ATTR_PHONE = "phone";
		final String ATTR_MOBILE = "mobile";
		final String ATTR_LIBRARY_ID = "library_id";
		final String ATTR_CATEGORY_ID = "category_id";
		final String ATTR_DATE_OF_BIRTH = "date_of_birth";
		final String ATTR_EXPIRY_DATE = "expiry_date";
		final String ATTR_DATE_ENROLLED = "date_enrolled";
		final String ATTR_DATE_RENEWED = "date_renewed";
		final String ATTR_GENDER = "gender";
		final String ATTR_ADDRESS = "address";
		final String ATTR_ADDRESS2 = "address2";
		final String ATTR_CITY = "city";
		final String ATTR_STATE = "state";
		final String ATTR_POSTAL_CODE = "postal_code";
		final String ATTR_COUNTRY = "country";
		final String ATTR_STAFF_NOTES = "staff_notes";
		final String ATTR_OPAC_NOTES = "opac_notes";
		final String ATTR_INCORRECT_ADDRESS = "incorrect_address";
		final String ATTR_PATRON_CARD_LOST = "patron_card_lost";
		final String ATTR_EXPIRED = "expired";
		final String ATTR_RESTRICTED = "restricted";
		final String ATTR_AUTORENEW_CHECKOUTS = "autorenew_checkouts";
		final String ATTR_ANONYMIZED = "anonymized";
		final String ATTR_PROTECTED = "protected";
		final String ATTR_UPDATED_ON = "updated_on";
		final String ATTR_LAST_SEEN = "last_seen";

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

		MANAGED_KOHA_PATRON_CONNID_NAMES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
				ATTR_USERID, ATTR_CARDNUMBER, ATTR_SURNAME, ATTR_FIRSTNAME, ATTR_EMAIL, ATTR_PHONE, ATTR_MOBILE,
				ATTR_LIBRARY_ID, ATTR_CATEGORY_ID, ATTR_DATE_OF_BIRTH, ATTR_EXPIRY_DATE,
				ATTR_GENDER, ATTR_ADDRESS, ATTR_ADDRESS2, ATTR_CITY, ATTR_STATE, ATTR_POSTAL_CODE, ATTR_COUNTRY,
				ATTR_STAFF_NOTES, ATTR_OPAC_NOTES,
				ATTR_INCORRECT_ADDRESS, ATTR_PATRON_CARD_LOST, ATTR_RESTRICTED, ATTR_AUTORENEW_CHECKOUTS,
				ATTR_PROTECTED
		)));
	}

	// --- Definiciones para GRUPOS (Categories) ---
	private static final Map<String, AttributeMetadata> KOHA_CATEGORY_ATTRIBUTE_METADATA = new HashMap<>();
	private static final Set<String> MANAGED_KOHA_CATEGORY_CONNID_NAMES;

	static {
		final String ATTR_CATEGORY_NAME = "name";
		final String ATTR_CATEGORY_TYPE = "category_type";
		final String ATTR_CATEGORY_ENROLMENT_PERIOD = "enrolment_period";
		final String ATTR_CATEGORY_MIN_PASS_LENGTH = "min_password_length";
		final String ATTR_CATEGORY_STRONG_PASS = "require_strong_password";
		final String ATTR_CATEGORY_UPPER_AGE_LIMIT = "upper_age_limit";
		final String ATTR_CATEGORY_LOWER_AGE_LIMIT = "lower_age_limit";

		KOHA_CATEGORY_ATTRIBUTE_METADATA.put(ATTR_CATEGORY_NAME, new AttributeMetadata(ATTR_CATEGORY_NAME, "name", String.class, AttributeMetadata.Flags.REQUIRED));
		KOHA_CATEGORY_ATTRIBUTE_METADATA.put(ATTR_CATEGORY_TYPE, new AttributeMetadata(ATTR_CATEGORY_TYPE, "category_type", String.class));
		KOHA_CATEGORY_ATTRIBUTE_METADATA.put(ATTR_CATEGORY_ENROLMENT_PERIOD, new AttributeMetadata(ATTR_CATEGORY_ENROLMENT_PERIOD, "enrolment_period", Integer.class));
		KOHA_CATEGORY_ATTRIBUTE_METADATA.put(ATTR_CATEGORY_MIN_PASS_LENGTH, new AttributeMetadata(ATTR_CATEGORY_MIN_PASS_LENGTH, "min_password_length", Integer.class, AttributeMetadata.Flags.NOT_CREATABLE, AttributeMetadata.Flags.NOT_UPDATEABLE));
		KOHA_CATEGORY_ATTRIBUTE_METADATA.put(ATTR_CATEGORY_STRONG_PASS, new AttributeMetadata(ATTR_CATEGORY_STRONG_PASS, "require_strong_password", Boolean.class, AttributeMetadata.Flags.NOT_CREATABLE, AttributeMetadata.Flags.NOT_UPDATEABLE));
		KOHA_CATEGORY_ATTRIBUTE_METADATA.put(ATTR_CATEGORY_UPPER_AGE_LIMIT, new AttributeMetadata(ATTR_CATEGORY_UPPER_AGE_LIMIT, "upper_age_limit", Integer.class, AttributeMetadata.Flags.NOT_CREATABLE, AttributeMetadata.Flags.NOT_UPDATEABLE));
		KOHA_CATEGORY_ATTRIBUTE_METADATA.put(ATTR_CATEGORY_LOWER_AGE_LIMIT, new AttributeMetadata(ATTR_CATEGORY_LOWER_AGE_LIMIT, "lower_age_limit", Integer.class, AttributeMetadata.Flags.NOT_CREATABLE, AttributeMetadata.Flags.NOT_UPDATEABLE));

		MANAGED_KOHA_CATEGORY_CONNID_NAMES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
				ATTR_CATEGORY_NAME, ATTR_CATEGORY_TYPE, ATTR_CATEGORY_ENROLMENT_PERIOD,
				ATTR_CATEGORY_MIN_PASS_LENGTH, ATTR_CATEGORY_STRONG_PASS, ATTR_CATEGORY_UPPER_AGE_LIMIT, ATTR_CATEGORY_LOWER_AGE_LIMIT
		)));
	}

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

		// --- Account Object Class (Patrons) ---
		ObjectClassInfoBuilder accountBuilder = new ObjectClassInfoBuilder();
		accountBuilder.setType(ObjectClass.ACCOUNT_NAME);
		Set<String> patronAttrNamesToProcess = new HashSet<>(KOHA_PATRON_ATTRIBUTE_METADATA.keySet());

		accountBuilder.addAttributeInfo(AttributeInfoBuilder.define(Uid.NAME)
				.setNativeName(KOHA_PATRON_ID_NATIVE_NAME).setType(String.class)
				.setRequired(true).setCreateable(false).setUpdateable(false).setReadable(true).build());
		patronAttrNamesToProcess.remove("userid");

		AttributeMetadata accountNameMeta = KOHA_PATRON_ATTRIBUTE_METADATA.get("userid");
		if (accountNameMeta != null) {
			accountBuilder.addAttributeInfo(AttributeInfoBuilder.define(Name.NAME)
					.setNativeName(accountNameMeta.kohaNativeName).setType(accountNameMeta.type)
					.setRequired(accountNameMeta.flags.contains(AttributeMetadata.Flags.REQUIRED))
					.setCreateable(!accountNameMeta.flags.contains(AttributeMetadata.Flags.NOT_CREATABLE))
					.setUpdateable(!accountNameMeta.flags.contains(AttributeMetadata.Flags.NOT_UPDATEABLE))
					.setReadable(!accountNameMeta.flags.contains(AttributeMetadata.Flags.NOT_READABLE)).build());
			patronAttrNamesToProcess.remove("userid");
		}

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

		AttributeMetadata groupNameMeta = KOHA_CATEGORY_ATTRIBUTE_METADATA.get("name");
		if (groupNameMeta != null) {
			groupBuilder.addAttributeInfo(AttributeInfoBuilder.define(Name.NAME)
					.setNativeName(groupNameMeta.kohaNativeName).setType(groupNameMeta.type)
					.setRequired(groupNameMeta.flags.contains(AttributeMetadata.Flags.REQUIRED))
					.setCreateable(!groupNameMeta.flags.contains(AttributeMetadata.Flags.NOT_CREATABLE))
					.setUpdateable(!groupNameMeta.flags.contains(AttributeMetadata.Flags.NOT_UPDATEABLE))
					.setReadable(!groupNameMeta.flags.contains(AttributeMetadata.Flags.NOT_READABLE)).build());
			categoryAttrNamesToProcess.remove("name");
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

		this.connectorSchema = schemaBuilder.build();
		LOG.ok("Schema built successfully.");
		return this.connectorSchema;
	}

	private String getValidOAuthToken() throws IOException {
		synchronized (tokenLock) {
			long nowEpochSeconds = System.currentTimeMillis() / 1000;
			if (StringUtil.isNotBlank(oauthAccessToken) &&
					oauthTokenExpiryEpoch > nowEpochSeconds + TOKEN_EXPIRY_BUFFER_SECONDS) {
				LOG.ok("OAUTH: Reutilizando token de acceso existente.");
				return oauthAccessToken;
			}
			LOG.ok("OAUTH: Solicitud de nuevo token de acceso...");
			RestUsersConfiguration config = getConfiguration();
			String tokenUrl = config.getServiceAddress() + API_BASE_PATH + "/oauth/token";
			HttpPost tokenRequest = new HttpPost(tokenUrl);
			tokenRequest.setHeader("Content-Type", "application/x-www-form-urlencoded");
			tokenRequest.setHeader("Accept", "application/json");
			final StringBuilder secretBuilder = new StringBuilder();
			config.getClientSecret().access(c -> secretBuilder.append(c));
			String clientSecret = secretBuilder.toString();
			List<NameValuePair> formParams = new ArrayList<>();
			formParams.add(new BasicNameValuePair("grant_type", "client_credentials"));
			formParams.add(new BasicNameValuePair("client_id", config.getClientId()));
			formParams.add(new BasicNameValuePair("client_secret", clientSecret));
			tokenRequest.setEntity(new UrlEncodedFormEntity(formParams, StandardCharsets.UTF_8));
			try (CloseableHttpResponse response = execute(tokenRequest)) {
				processResponseErrors(response);
				String body = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
				JSONObject json = new JSONObject(body);
				oauthAccessToken = json.getString("access_token");
				int expiresIn = json.optInt("expires_in", 3600);
				oauthTokenExpiryEpoch = nowEpochSeconds + expiresIn;
				LOG.ok("OAUTH: Nuevo token obtenido. Expira en {0} segundos.", expiresIn);
				return oauthAccessToken;
			} catch (IOException | JSONException e) {
				LOG.error("OAUTH: Error al obtener token: {0}", e.getMessage(), e);
				this.oauthAccessToken = null;
				this.oauthTokenExpiryEpoch = 0L;
				throw new ConnectorIOException("OAUTH: Falló la solicitud de token: " + e.getMessage(), e);
			}
		}
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

	private JSONObject getPatron(String uid) throws IOException {
		String endpoint = getConfiguration().getServiceAddress() + API_BASE_PATH + PATRONS_ENDPOINT_SUFFIX + "/" + uid;
		HttpGet request = new HttpGet(endpoint);
		String responseBody = callRequest(request);
		return new JSONObject(responseBody);
	}

	@Override
	public Uid update(ObjectClass oClass, Uid uid, Set<Attribute> attrs, OperationOptions opts) {
		if (attrs == null || attrs.isEmpty()) {
			LOG.info("UPDATE: No attributes to change for UID={0}. Returning UID.", uid.getUidValue());
			return uid;
		}
		if (ObjectClass.ACCOUNT.is(oClass.getObjectClassValue())) {
			try {
				JSONObject patronJson = getPatron(uid.getUidValue());
				LOG.ok("UPDATE: Fetched existing patron with UID={0}", uid.getUidValue());
				ObjectClassInfo oci = schema().findObjectClassInfo(ObjectClass.ACCOUNT_NAME);
				JSONObject changesJson = buildJsonForKoha(attrs, oci, KOHA_PATRON_ATTRIBUTE_METADATA, MANAGED_KOHA_PATRON_CONNID_NAMES, false);
				LOG.info("UPDATE: Applying changes to patron UID={0}: {1}", uid.getUidValue(), changesJson.toString());
				for (String key : changesJson.keySet()) {
					patronJson.put(key, changesJson.get(key));
				}

				LOG.ok("Cleaning payload before PUT. Original keys: {0}", patronJson.keySet());
				for (AttributeMetadata meta : KOHA_PATRON_ATTRIBUTE_METADATA.values()) {
					if (meta.flags.contains(AttributeMetadata.Flags.NOT_UPDATEABLE) || meta.flags.contains(AttributeMetadata.Flags.NOT_CREATABLE)) {
						if (patronJson.has(meta.kohaNativeName)) {
							patronJson.remove(meta.kohaNativeName);
						}
					}
				}
				LOG.ok("Cleaned payload for PUT. Final keys: {0}", patronJson.keySet());

				String endpoint = getConfiguration().getServiceAddress() + API_BASE_PATH + PATRONS_ENDPOINT_SUFFIX + "/" + uid.getUidValue();
				HttpPut request = new HttpPut(endpoint);
				callRequest(request, patronJson);
				LOG.ok("UPDATE: Koha patron object updated successfully. UID={0}", uid.getUidValue());
				return uid;
			} catch (IOException e) {
				throw new ConnectorIOException("Failed to update patron in Koha for UID " + uid.getUidValue() + ": " + e.getMessage(), e);
			}
		} else if (ObjectClass.GROUP.is(oClass.getObjectClassValue())) {
			ObjectClassInfo oci = schema().findObjectClassInfo(ObjectClass.GROUP_NAME);
			JSONObject payload = buildJsonForKoha(attrs, oci, KOHA_CATEGORY_ATTRIBUTE_METADATA, MANAGED_KOHA_CATEGORY_CONNID_NAMES, false);
			LOG.info("UPDATE: Payload for Koha Group: {0}", payload.toString());
			String endpoint = getConfiguration().getServiceAddress() + API_BASE_PATH + CATEGORIES_ENDPOINT_SUFFIX + "/" + uid.getUidValue();
			HttpPut request = new HttpPut(endpoint);
			callRequest(request, payload);
			LOG.ok("UPDATE: Koha group object updated. UID={0}", uid.getUidValue());
			return uid;
		} else {
			throw new UnsupportedOperationException("Update unsupported for: " + oClass.getObjectClassValue());
		}
	}

	private JSONObject buildJsonForKoha(Set<Attribute> attributes, ObjectClassInfo oci,
										Map<String, AttributeMetadata> metadataMap,
										Set<String> managedConnIdNames, boolean isCreate) {
		JSONObject jo = new JSONObject();
		Set<String> processedKohaAttrs = new HashSet<>();
		final String nameAttribute;
		if (ObjectClass.ACCOUNT.is(oci.getType())) {
			nameAttribute = "userid";
		} else if (ObjectClass.GROUP.is(oci.getType())) {
			nameAttribute = "name";
		} else {
			nameAttribute = null;
		}

		for (Attribute attr : attributes) {
			String connIdAttrName = attr.getName();
			if (Uid.NAME.equals(connIdAttrName)) continue;

			AttributeMetadata meta = Name.NAME.equals(connIdAttrName) ?
					metadataMap.get(nameAttribute) : metadataMap.get(connIdAttrName);

			if (meta == null) {
				LOG.warn("BUILD_JSON: No metadata for ConnId Attr '{0}'. Skipping.", connIdAttrName);
				continue;
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
			if (KOHA_PATRON_ATTRIBUTE_METADATA.containsKey(connIdAttrName)) {
				AttributeMetadata meta = KOHA_PATRON_ATTRIBUTE_METADATA.get(connIdAttrName);
				if (meta.kohaNativeName.equals("date_of_birth") || meta.kohaNativeName.equals("expiry_date") ||
						meta.kohaNativeName.equals("date_enrolled") || meta.kohaNativeName.equals("date_renewed")) {
					try {
						LocalDate.parse(connIdValue.toString(), KOHA_DATE_FORMATTER);
						return connIdValue.toString();
					} catch (DateTimeParseException e) {
						throw new InvalidAttributeValueException("Invalid date " + connIdValue + " for " + connIdAttrName, e);
					}
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
						if (StringUtil.isNotBlank(filter.getByName())) queryParams.add(metaMap.get("userid").kohaNativeName + "=" + urlEncodeUTF8(filter.getByName()));
						if (StringUtil.isNotBlank(filter.getByEmail())) queryParams.add(metaMap.get("email").kohaNativeName + "=" + urlEncodeUTF8(filter.getByEmail()));
						if (filter.getByCardNumber() != null) queryParams.add(metaMap.get("cardnumber").kohaNativeName + "=" + urlEncodeUTF8(filter.getByCardNumber()));
					} else if (ObjectClass.GROUP.is(oClass.getObjectClassValue()) && StringUtil.isNotBlank(filter.getByName())) {
						queryParams.add(metaMap.get("name").kohaNativeName + "=" + urlEncodeUTF8(filter.getByName()));
					}
				}

				HttpGet request = new HttpGet(fullBaseUrl + "?" + String.join("&", queryParams));
				LOG.ok("EXECUTE_QUERY: URL: {0}", request.getURI());
				String response = callRequest(request);
				JSONArray items = null;

				try {
					items = new JSONArray(response);
				} catch (JSONException e) {
					try {
						JSONObject json = new JSONObject(response);
						if (ObjectClass.ACCOUNT.is(oClass.getObjectClassValue())) {
							items = json.optJSONArray("patrons");
						} else if (ObjectClass.GROUP.is(oClass.getObjectClassValue())) {
							items = json.optJSONArray("patron_categories");
						}
						if (items == null) {
							throw new ConnectorException("Invalid JSON response from Koha: " + response, e);
						}
					} catch (JSONException ex) {
						throw new ConnectorException("Invalid JSON response from Koha: " + response, ex);
					}
				}

				if (items.length() == 0) {
					moreResults = false;
				} else {
					for (int i = 0; i < items.length(); i++) {
						ConnectorObject co = convertKohaJsonToConnectorObject(items.getJSONObject(i), oci, metaMap);
						if (co != null && !handler.handle(co)) {
							moreResults = false;
							break;
						}
					}
					if (moreResults) {
						moreResults = (items.length() == pageSize);
						if (moreResults) currentPage++;
					}
				}
			} while (moreResults);
		} catch (IOException | JSONException e) {
			throw new ConnectorIOException("Query failed: " + e.getMessage(), e);
		}
	}

	private ConnectorObject convertKohaJsonToConnectorObject(JSONObject kohaJson, ObjectClassInfo oci, Map<String, AttributeMetadata> metadataMap) {
		if (kohaJson == null) {
			return null;
		}
		ConnectorObjectBuilder builder = new ConnectorObjectBuilder().setObjectClass(new ObjectClass(oci.getType()));

		for (AttributeInfo attrInfo : oci.getAttributeInfo()) {
			String connIdName = attrInfo.getName();
			if (Uid.NAME.equals(connIdName) || Name.NAME.equals(connIdName)) {
				continue;
			}
			AttributeMetadata meta = metadataMap.get(connIdName);
			if (meta == null || meta.kohaNativeName == null || (meta.flags != null && meta.flags.contains(AttributeMetadata.Flags.NOT_READABLE)) ||
					!kohaJson.has(meta.kohaNativeName) || kohaJson.isNull(meta.kohaNativeName)) {
				continue;
			}
			Object kohaNativeVal = kohaJson.get(meta.kohaNativeName);
			Object connIdVal = convertKohaValueToConnIdValue(kohaNativeVal, meta.type, meta.connIdName);
			if (connIdVal == null) {
				continue;
			}
			if (attrInfo.isMultiValued()) {
				if (kohaNativeVal instanceof JSONArray) {
					List<Object> multiVals = new ArrayList<>();
					((JSONArray) kohaNativeVal).forEach(item -> {
						Object convItem = convertKohaValueToConnIdValue(item, meta.type, meta.connIdName);
						if (convItem != null) multiVals.add(convItem);
					});
					if (!multiVals.isEmpty()) {
						builder.addAttribute(AttributeBuilder.build(meta.connIdName, multiVals));
					}
				} else {
					builder.addAttribute(AttributeBuilder.build(meta.connIdName, Collections.singletonList(connIdVal)));
				}
			} else {
				builder.addAttribute(AttributeBuilder.build(meta.connIdName, connIdVal));
			}
		}

		String uidVal, nameVal;

		if (ObjectClass.ACCOUNT.is(oci.getType())) {
			uidVal = kohaJson.optString(KOHA_PATRON_ID_NATIVE_NAME);
			nameVal = kohaJson.optString(KOHA_PATRON_ATTRIBUTE_METADATA.get("userid").kohaNativeName);
		} else if (ObjectClass.GROUP.is(oci.getType())) {
			uidVal = kohaJson.optString(KOHA_CATEGORY_ID_NATIVE_NAME);
			nameVal = kohaJson.optString(KOHA_CATEGORY_ATTRIBUTE_METADATA.get("name").kohaNativeName);
		} else {
			return null;
		}

		if (StringUtil.isBlank(uidVal)) {
			LOG.error("CONVERT_JSON: Se encontró un registro sin UID. Se omitirá. JSON: {0}", kohaJson.toString());
			return null;
		}
		if (StringUtil.isBlank(nameVal)) {
			LOG.warn("CONVERT_JSON: El registro con UID={0} no tiene un nombre. Usando UID como nombre.", uidVal);
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
				if (KOHA_PATRON_ATTRIBUTE_METADATA.containsKey(connIdAttrName)) {
					AttributeMetadata meta = KOHA_PATRON_ATTRIBUTE_METADATA.get(connIdAttrName);
					if (meta.kohaNativeName.equals("date_of_birth") || meta.kohaNativeName.equals("expiry_date") ||
							meta.kohaNativeName.equals("date_enrolled") || meta.kohaNativeName.equals("date_renewed")) {
						return LocalDate.parse(kohaValue.toString(), KOHA_DATE_FORMATTER).toString();
					} else if (meta.kohaNativeName.equals("updated_on") || meta.kohaNativeName.equals("last_seen")) {
						return ZonedDateTime.parse(kohaValue.toString(), KOHA_DATETIME_FORMATTER).toString();
					}
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
		request.setHeader("Accept-Encoding", "gzip");
		addAuthHeader(request);
		try (CloseableHttpResponse response = execute(request)) {
			processResponseErrors(response);
			HttpEntity entity = response.getEntity();
			InputStream inputStream = entity.getContent();
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