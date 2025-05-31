package com.identicum.connectors;

import com.evolveum.polygon.rest.AbstractRestConnector;
import org.apache.commons.codec.binary.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.ByteArrayEntity;
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
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ConnectorClass(displayNameKey = "connector.identicum.rest.display", configurationClass = RestUsersConfiguration.class)
public class RestUsersConnector
		extends AbstractRestConnector<RestUsersConfiguration>
		implements CreateOp, UpdateOp, SchemaOp, SearchOp<RestUsersFilter>, DeleteOp, TestOp {

	private static final Log LOG = Log.getLog(RestUsersConnector.class);

	//region Constantes de API y Endpoints
	private static final String API_BASE_PATH = "/api/v1";
	private static final String PATRONS_ENDPOINT_SUFFIX = "/patrons";
	private static final String CATEGORIES_ENDPOINT_SUFFIX = "/patron_categories";
	//endregion

	//region Nombres de Atributos de Koha y ConnId
	public static final String KOHA_PATRON_ID_NATIVE_NAME = "patron_id";
	public static final String KOHA_CATEGORY_ID_NATIVE_NAME = "patron_category_id"; // ¡CONFIRMAR: o "categorycode"?

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

	public static final String ATTR_CATEGORY_DESCRIPTION = "description"; // ¡CONFIRMAR: __NAME__ para __GROUP__?
	public static final String ATTR_CATEGORY_TYPE = "category_type";
	public static final String ATTR_CATEGORY_ENROLMENT_PERIOD = "enrolment_period";
	//endregion

	//region Formateadores de Fecha/Hora
	private static final DateTimeFormatter KOHA_DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;
	private static final DateTimeFormatter KOHA_DATETIME_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
	//endregion

	private volatile Schema connectorSchema = null;

	//region Definición de Metadatos de Atributos
	private static class AttributeMetadata {
		final String connIdName;
		final String kohaNativeName;
		final Class<?> type;
		final Set<Flags> flags;
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
	//endregion

	@Override
	public Schema schema() {
		if (this.connectorSchema != null) {
			return this.connectorSchema;
		}
		LOG.ok("Building schema for Koha Connector");
		SchemaBuilder schemaBuilder = new SchemaBuilder(RestUsersConnector.class);

		// --- ObjectClass __ACCOUNT__ (Patrons) ---
		ObjectClassInfoBuilder accountBuilder = new ObjectClassInfoBuilder();
		accountBuilder.setType(ObjectClass.ACCOUNT_NAME);
		Set<String> accountAttrsDefined = new HashSet<>();

		accountBuilder.addAttributeInfo(
				AttributeInfoBuilder.define(Uid.NAME)
						.setNativeName(KOHA_PATRON_ID_NATIVE_NAME)
						.setType(String.class)
						.setRequired(true).setCreateable(false).setUpdateable(false).setReadable(true)
						.build());
		accountAttrsDefined.add(Uid.NAME);

		AttributeMetadata nameAttrMeta = KOHA_PATRON_ATTRIBUTE_METADATA.get(ATTR_USERID);
		if (nameAttrMeta != null) {
			accountBuilder.addAttributeInfo(
					AttributeInfoBuilder.define(Name.NAME)
							.setNativeName(nameAttrMeta.kohaNativeName)
							.setType(nameAttrMeta.type)
							.setRequired(true)
							.setCreateable(!nameAttrMeta.flags.contains(AttributeMetadata.Flags.NOT_CREATABLE))
							.setUpdateable(!nameAttrMeta.flags.contains(AttributeMetadata.Flags.NOT_UPDATEABLE))
							.setReadable(!nameAttrMeta.flags.contains(AttributeMetadata.Flags.NOT_READABLE))
							.build());
			accountAttrsDefined.add(Name.NAME);
		}

		for (String connIdAttrName : MANAGED_KOHA_PATRON_CONNID_NAMES) {
			if (accountAttrsDefined.contains(connIdAttrName)) {
				continue;
			}
			AttributeMetadata meta = KOHA_PATRON_ATTRIBUTE_METADATA.get(connIdAttrName);
			if (meta != null) {
				AttributeInfoBuilder attrBuilder = AttributeInfoBuilder.define(meta.connIdName)
						.setNativeName(meta.kohaNativeName)
						.setType(meta.type)
						.setRequired(meta.flags.contains(AttributeMetadata.Flags.REQUIRED))
						.setMultiValued(meta.flags.contains(AttributeMetadata.Flags.MULTIVALUED))
						.setCreateable(!meta.flags.contains(AttributeMetadata.Flags.NOT_CREATABLE))
						.setUpdateable(!meta.flags.contains(AttributeMetadata.Flags.NOT_UPDATEABLE))
						.setReadable(!meta.flags.contains(AttributeMetadata.Flags.NOT_READABLE));
				accountBuilder.addAttributeInfo(attrBuilder.build());
				accountAttrsDefined.add(meta.connIdName);
			} else {
				LOG.warn("Schema: Metadata not found for managed patron attribute '{0}'. Skipping.", connIdAttrName);
			}
		}

		KOHA_PATRON_ATTRIBUTE_METADATA.forEach((connIdName, meta) -> {
			if (!accountAttrsDefined.contains(connIdName)) {
				if (meta.flags.contains(AttributeMetadata.Flags.NOT_CREATABLE) && meta.flags.contains(AttributeMetadata.Flags.NOT_UPDATEABLE)) {
					AttributeInfoBuilder attrBuilder = AttributeInfoBuilder.define(meta.connIdName)
							.setNativeName(meta.kohaNativeName)
							.setType(meta.type)
							.setMultiValued(meta.flags.contains(AttributeMetadata.Flags.MULTIVALUED))
							.setCreateable(false).setUpdateable(false).setReadable(true);
					accountBuilder.addAttributeInfo(attrBuilder.build());
					accountAttrsDefined.add(meta.connIdName);
				}
			}
		});
		schemaBuilder.defineObjectClass(accountBuilder.build());

		// --- ObjectClass __GROUP__ (Patron Categories) ---
		ObjectClassInfoBuilder groupBuilder = new ObjectClassInfoBuilder();
		groupBuilder.setType(ObjectClass.GROUP_NAME);
		Set<String> groupAttrsDefined = new HashSet<>();

		groupBuilder.addAttributeInfo(
				AttributeInfoBuilder.define(Uid.NAME)
						.setNativeName(KOHA_CATEGORY_ID_NATIVE_NAME)
						.setType(String.class)
						.setRequired(true).setCreateable(false).setUpdateable(false).setReadable(true)
						.build());
		groupAttrsDefined.add(Uid.NAME);

		AttributeMetadata groupNameMeta = KOHA_CATEGORY_ATTRIBUTE_METADATA.get(ATTR_CATEGORY_DESCRIPTION);
		if (groupNameMeta != null) {
			groupBuilder.addAttributeInfo(
					AttributeInfoBuilder.define(Name.NAME)
							.setNativeName(groupNameMeta.kohaNativeName)
							.setType(groupNameMeta.type)
							.setRequired(true)
							.setCreateable(!groupNameMeta.flags.contains(AttributeMetadata.Flags.NOT_CREATABLE))
							.setUpdateable(!groupNameMeta.flags.contains(AttributeMetadata.Flags.NOT_UPDATEABLE))
							.setReadable(!groupNameMeta.flags.contains(AttributeMetadata.Flags.NOT_READABLE))
							.build());
			groupAttrsDefined.add(Name.NAME);
		}

		for (String connIdAttrName : MANAGED_KOHA_CATEGORY_CONNID_NAMES) {
			if (groupAttrsDefined.contains(connIdAttrName)) {
				continue;
			}
			AttributeMetadata meta = KOHA_CATEGORY_ATTRIBUTE_METADATA.get(connIdAttrName);
			if (meta != null) {
				AttributeInfoBuilder attrBuilder = AttributeInfoBuilder.define(meta.connIdName)
						.setNativeName(meta.kohaNativeName)
						.setType(meta.type)
						.setRequired(meta.flags.contains(AttributeMetadata.Flags.REQUIRED))
						.setMultiValued(meta.flags.contains(AttributeMetadata.Flags.MULTIVALUED))
						.setCreateable(!meta.flags.contains(AttributeMetadata.Flags.NOT_CREATABLE))
						.setUpdateable(!meta.flags.contains(AttributeMetadata.Flags.NOT_UPDATEABLE))
						.setReadable(!meta.flags.contains(AttributeMetadata.Flags.NOT_READABLE));
				groupBuilder.addAttributeInfo(attrBuilder.build());
				groupAttrsDefined.add(meta.connIdName);
			} else {
				LOG.warn("Schema: Metadata not found for managed category attribute '{0}'. Skipping.", connIdAttrName);
			}
		}

		KOHA_CATEGORY_ATTRIBUTE_METADATA.forEach((connIdName, meta) -> {
			if (!groupAttrsDefined.contains(connIdName)) {
				if (meta.flags.contains(AttributeMetadata.Flags.NOT_CREATABLE) && meta.flags.contains(AttributeMetadata.Flags.NOT_UPDATEABLE)) {
					AttributeInfoBuilder attrBuilder = AttributeInfoBuilder.define(meta.connIdName)
							.setNativeName(meta.kohaNativeName)
							.setType(meta.type)
							.setMultiValued(meta.flags.contains(AttributeMetadata.Flags.MULTIVALUED))
							.setCreateable(false).setUpdateable(false).setReadable(true);
					groupBuilder.addAttributeInfo(attrBuilder.build());
				}
			}
		});
		schemaBuilder.defineObjectClass(groupBuilder.build());

		this.connectorSchema = schemaBuilder.build();
		LOG.ok("Schema built successfully.");
		return this.connectorSchema;
	}

	@Override
	public Uid create(ObjectClass objectClass, Set<Attribute> attributes, OperationOptions operationOptions) {
		LOG.ok("CREATE: ObjectClass={0}", objectClass);
		String endpointSuffix;
		ObjectClassInfo oci;
		Map<String, AttributeMetadata> metadataMap;
		Set<String> managedNames;

		if (ObjectClass.ACCOUNT.is(objectClass.getObjectClassValue())) {
			endpointSuffix = PATRONS_ENDPOINT_SUFFIX;
			oci = schema().findObjectClassInfo(ObjectClass.ACCOUNT_NAME);
			metadataMap = KOHA_PATRON_ATTRIBUTE_METADATA;
			managedNames = MANAGED_KOHA_PATRON_CONNID_NAMES;
		} else if (ObjectClass.GROUP.is(objectClass.getObjectClassValue())) {
			endpointSuffix = CATEGORIES_ENDPOINT_SUFFIX;
			oci = schema().findObjectClassInfo(ObjectClass.GROUP_NAME);
			metadataMap = KOHA_CATEGORY_ATTRIBUTE_METADATA;
			managedNames = MANAGED_KOHA_CATEGORY_CONNID_NAMES;
		} else {
			throw new UnsupportedOperationException("Only Account or Group objects can be created.");
		}

		JSONObject kohaJsonPayload = buildJsonForKoha(attributes, oci, metadataMap, managedNames, true);
		LOG.info("CREATE: Payload for Koha ({0}): {1}", oci.getType(), kohaJsonPayload.toString());

		String endpoint = getConfiguration().getServiceAddress() + API_BASE_PATH + endpointSuffix;
		HttpPost request = new HttpPost(endpoint);
		JSONObject responseJson = callRequest(request, kohaJsonPayload);

		String newNativeUid = null;
		if (ObjectClass.ACCOUNT.is(objectClass.getObjectClassValue())) {
			newNativeUid = responseJson.optString(KOHA_PATRON_ID_NATIVE_NAME);
		} else { // GROUP
			newNativeUid = responseJson.optString(KOHA_CATEGORY_ID_NATIVE_NAME);
		}

		if (StringUtil.isBlank(newNativeUid)) {
			throw new ConnectorException("Koha response for CREATE did not contain a usable native UID. Response: " + responseJson.toString());
		}

		LOG.ok("CREATE: Koha object ({0}) created successfully. Native UID={1}", oci.getType(), newNativeUid);
		return new Uid(newNativeUid);
	}

	@Override
	public Uid update(ObjectClass objectClass, Uid uid, Set<Attribute> attributes, OperationOptions operationOptions) {
		LOG.ok("UPDATE: ObjectClass={0}, UID={1}", objectClass, uid.getUidValue());
		String endpointSuffix;
		ObjectClassInfo oci;
		Map<String, AttributeMetadata> metadataMap;
		Set<String> managedNames;

		if (ObjectClass.ACCOUNT.is(objectClass.getObjectClassValue())) {
			endpointSuffix = PATRONS_ENDPOINT_SUFFIX;
			oci = schema().findObjectClassInfo(ObjectClass.ACCOUNT_NAME);
			metadataMap = KOHA_PATRON_ATTRIBUTE_METADATA;
			managedNames = MANAGED_KOHA_PATRON_CONNID_NAMES;
		} else if (ObjectClass.GROUP.is(objectClass.getObjectClassValue())) {
			endpointSuffix = CATEGORIES_ENDPOINT_SUFFIX;
			oci = schema().findObjectClassInfo(ObjectClass.GROUP_NAME);
			metadataMap = KOHA_CATEGORY_ATTRIBUTE_METADATA;
			managedNames = MANAGED_KOHA_CATEGORY_CONNID_NAMES;
		} else {
			throw new UnsupportedOperationException("Only Account or Group objects can be updated.");
		}

		if (attributes == null || attributes.isEmpty()) {
			LOG.info("UPDATE: No attributes to update for UID={0}", uid.getUidValue());
			return uid;
		}

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
		JSONObject jo = new JSONObject();
		Set<String> processedKohaAttrs = new HashSet<>();

		for (Attribute attr : attributes) {
			String connIdAttrName = attr.getName();
			List<Object> values = attr.getValue();

			if (Uid.NAME.equals(connIdAttrName)) {
				continue;
			}

			AttributeMetadata meta = metadataMap.get(connIdAttrName);
			if (Name.NAME.equals(connIdAttrName)) {
				if (ObjectClass.ACCOUNT_NAME.equals(oci.getType())) {
					meta = metadataMap.get(ATTR_USERID);
				} else {
					meta = metadataMap.get(ATTR_CATEGORY_DESCRIPTION);
				}
			}

			if (meta == null) {
				LOG.warn("BUILD_JSON: Metadata not found for ConnId Attribute '{0}'. Skipping.", connIdAttrName);
				continue;
			}
			String kohaAttrName = meta.kohaNativeName;

			if (!managedConnIdNames.contains(meta.connIdName)) {
				LOG.info("BUILD_JSON: ConnId Attribute '{0}' (Koha: '{1}') is not in its managed set. Skipping.", meta.connIdName, kohaAttrName);
				continue;
			}
			if (isCreate && meta.flags.contains(AttributeMetadata.Flags.NOT_CREATABLE)) {
				LOG.info("BUILD_JSON: Attribute '{0}' (Koha: '{1}') is not creatable. Skipping for CREATE.", meta.connIdName, kohaAttrName);
				continue;
			}
			if (!isCreate && meta.flags.contains(AttributeMetadata.Flags.NOT_UPDATEABLE)) {
				LOG.info("BUILD_JSON: Attribute '{0}' (Koha: '{1}') is not updateable. Skipping for UPDATE.", meta.connIdName, kohaAttrName);
				continue;
			}

			if (values == null || values.isEmpty() || values.get(0) == null) {
				if (!isCreate) {
					jo.put(kohaAttrName, JSONObject.NULL);
				}
				continue;
			}
			if (processedKohaAttrs.contains(kohaAttrName)) {
				continue;
			}

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
			jo.put(kohaAttrName, kohaValue);
			processedKohaAttrs.add(kohaAttrName);
		}
		return jo;
	}

	private Object convertConnIdValueToKohaJsonValue(Object connIdValue, Class<?> connIdType, String connIdAttrName) {
		if (connIdValue == null) {
			return JSONObject.NULL;
		}
		if (connIdType.equals(String.class)) {
			AttributeMetadata meta = KOHA_PATRON_ATTRIBUTE_METADATA.get(connIdAttrName);
			if (meta == null) meta = KOHA_CATEGORY_ATTRIBUTE_METADATA.get(connIdAttrName);

			if (meta != null && meta.kohaNativeName != null) {
				if (meta.kohaNativeName.equals(ATTR_DATE_OF_BIRTH) || meta.kohaNativeName.equals(ATTR_EXPIRY_DATE) ||
						meta.kohaNativeName.equals(ATTR_DATE_ENROLLED) || meta.kohaNativeName.equals(ATTR_DATE_RENEWED)) {
					try {
						LocalDate.parse(connIdValue.toString(), KOHA_DATE_FORMATTER);
						return connIdValue.toString();
					} catch (DateTimeParseException e) {
						LOG.error("Invalid date format for attribute {0}: {1}. Expected yyyy-MM-dd.", connIdAttrName, connIdValue);
						throw new InvalidAttributeValueException("Invalid date format for " + connIdAttrName + ": " + connIdValue);
					}
				}
			}
			return connIdValue.toString();
		} else if (connIdType.equals(Boolean.class)) {
			return connIdValue;
		} else if (connIdType.equals(Integer.class) || connIdType.equals(Long.class)) {
			return connIdValue;
		}
		return connIdValue.toString();
	}

	@Override
	public void delete(ObjectClass objectClass, Uid uid, OperationOptions operationOptions) {
		LOG.ok("DELETE: ObjectClass={0}, UID={1}", objectClass, uid.getUidValue());
		String endpointSuffix;
		if (ObjectClass.ACCOUNT.is(objectClass.getObjectClassValue())) {
			endpointSuffix = PATRONS_ENDPOINT_SUFFIX;
		} else if (ObjectClass.GROUP.is(objectClass.getObjectClassValue())) {
			endpointSuffix = CATEGORIES_ENDPOINT_SUFFIX;
		} else {
			throw new UnsupportedOperationException("Only Account or Group objects can be deleted.");
		}

		String endpoint = getConfiguration().getServiceAddress() + API_BASE_PATH + endpointSuffix + "/" + uid.getUidValue();
		HttpDelete request = new HttpDelete(endpoint);
		try {
			callRequest(request);
			LOG.ok("DELETE: Object with UID={0} deleted successfully from {1}", uid.getUidValue(), endpointSuffix);
		} catch (IOException e) {
			LOG.error("DELETE: Error deleting object with UID={0}. Message: {1}", uid.getUidValue(), e.getMessage());
			throw new ConnectorIOException("Error during HTTP call for DELETE: " + e.getMessage(), e);
		}
	}

	@Override
	public FilterTranslator<RestUsersFilter> createFilterTranslator(ObjectClass objectClass, OperationOptions operationOptions) {
		return new RestUsersFilterTranslator();
	}

	@Override
	public void executeQuery(ObjectClass objectClass, RestUsersFilter filter, ResultsHandler handler, OperationOptions options) {
		LOG.ok("EXECUTE_QUERY: ObjectClass={0}, Filter={1}, Options={2}", objectClass, filter, options);

		ObjectClassInfo oci = schema().findObjectClassInfo(objectClass.getObjectClassValue());
		if (oci == null) {
			throw new ConnectorException("Unsupported object class for query: " + objectClass.getObjectClassValue());
		}

		String baseEndpoint;
		Map<String, AttributeMetadata> metadataMap;

		if (ObjectClass.ACCOUNT.is(objectClass.getObjectClassValue())) {
			baseEndpoint = getConfiguration().getServiceAddress() + API_BASE_PATH + PATRONS_ENDPOINT_SUFFIX;
			metadataMap = KOHA_PATRON_ATTRIBUTE_METADATA;
		} else if (ObjectClass.GROUP.is(objectClass.getObjectClassValue())) {
			baseEndpoint = getConfiguration().getServiceAddress() + API_BASE_PATH + CATEGORIES_ENDPOINT_SUFFIX;
			metadataMap = KOHA_CATEGORY_ATTRIBUTE_METADATA;
		} else {
			LOG.warn("EXECUTE_QUERY: Unsupported ObjectClass {0}", objectClass);
			return;
		}

		try {
			int pageSize = options.getPageSize() != null ? options.getPageSize() : 100;
			int pageOffset = options.getPagedResultsOffset() != null ? options.getPagedResultsOffset() : 1;

			String queryParams = "?_per_page=" + pageSize + "&_page=" + pageOffset;

			if (filter != null && StringUtil.isNotBlank(filter.getByUid())) {
				String specificEndpoint = baseEndpoint + "/" + filter.getByUid();
				HttpGet request = new HttpGet(specificEndpoint); // Paginación no suele aplicar a GET by ID
				String responseStr = callRequest(request);
				JSONObject itemJson = new JSONObject(responseStr);
				ConnectorObject connectorObject = convertKohaJsonToConnectorObject(itemJson, oci, metadataMap);
				if (connectorObject != null) {
					handler.handle(connectorObject);
				}
				return;
			}

			if (filter != null && StringUtil.isNotBlank(filter.getByName())) {
				if (ObjectClass.ACCOUNT.is(objectClass.getObjectClassValue())) {
					queryParams += "&userid=" + filter.getByName(); // ¡CONFIRMAR PARÁMETRO!
				} else {
					queryParams += "&description=" + filter.getByName(); // ¡CONFIRMAR PARÁMETRO!
				}
			}
			if (filter != null && StringUtil.isNotBlank(filter.getByEmail())) {
				queryParams += "&email=" + filter.getByEmail(); // ¡CONFIRMAR PARÁMETRO!
			}


			boolean moreResults = true;
			while(moreResults) {
				HttpGet request = new HttpGet(baseEndpoint + queryParams);
				String responseStr = callRequest(request);
				JSONArray itemsArray = new JSONArray(responseStr);

				if (itemsArray.length() == 0) {
					moreResults = false;
					break;
				}

				for (int i = 0; i < itemsArray.length(); i++) {
					JSONObject itemJson = itemsArray.getJSONObject(i);
					ConnectorObject connectorObject = convertKohaJsonToConnectorObject(itemJson, oci, metadataMap);
					if (connectorObject != null && !handler.handle(connectorObject)) {
						moreResults = false;
						break;
					}
				}
				if (itemsArray.length() < pageSize) {
					moreResults = false;
				} else {
					pageOffset++;
					// Reconstruir queryParams para la siguiente página
					queryParams = "?_per_page=" + pageSize + "&_page=" + pageOffset;
					if (filter != null && StringUtil.isNotBlank(filter.getByName())) {
						if (ObjectClass.ACCOUNT.is(objectClass.getObjectClassValue())) {
							queryParams += "&userid=" + filter.getByName();
						} else { queryParams += "&description=" + filter.getByName();}
					}
					if (filter != null && StringUtil.isNotBlank(filter.getByEmail())) {
						queryParams += "&email=" + filter.getByEmail();
					}
				}
			}

		} catch (IOException | JSONException e) {
			LOG.error("EXECUTE_QUERY: Error during query. Message: {0}", e.getMessage(), e);
			throw new ConnectorIOException("Error during HTTP call or JSON processing for EXECUTE_QUERY: " + e.getMessage(), e);
		}
	}

	private ConnectorObject convertKohaJsonToConnectorObject(JSONObject kohaJson, ObjectClassInfo oci, Map<String, AttributeMetadata> metadataMap) {
		if (kohaJson == null) return null;

		// ConnectorObjectBuilder builder = new ConnectorObjectBuilder().setObjectClass(oci.getType()); // Línea original con error
		ConnectorObjectBuilder builder = new ConnectorObjectBuilder();
		builder.setObjectClass(new ObjectClass(oci.getType()));

		String uidValue = null;
		String nameValue = null;

		// Iterar sobre los metadatos definidos en el schema para asegurar que solo procesamos lo conocido
		for (AttributeInfo attrInfoFromSchema : oci.getAttributeInfo()) {
			String connIdAttrName = attrInfoFromSchema.getName();
			AttributeMetadata meta = metadataMap.get(connIdAttrName); // Buscar por nombre ConnId

			// Casos especiales para Uid.NAME y Name.NAME si su connIdName no está directamente en metadataMap
			// (aunque deberían estar si ATTR_USERID, etc. son los connIdName)
			if (Uid.NAME.equals(connIdAttrName)) {
				meta = new AttributeMetadata(Uid.NAME, KOHA_PATRON_ID_NATIVE_NAME, String.class); // Asumir String para UID
				if (ObjectClass.GROUP_NAME.equals(oci.getType())) {
					meta = new AttributeMetadata(Uid.NAME, KOHA_CATEGORY_ID_NATIVE_NAME, String.class);
				}
			} else if (Name.NAME.equals(connIdAttrName)) {
				if (ObjectClass.ACCOUNT_NAME.equals(oci.getType())) {
					meta = metadataMap.get(ATTR_USERID);
				} else { // GROUP
					meta = metadataMap.get(ATTR_CATEGORY_DESCRIPTION);
				}
			}


			if (meta == null || meta.kohaNativeName == null) {
				//LOG.warn("CONVERT_JSON: No metadata or native name for ConnId attribute '{0}'. Skipping.", connIdAttrName);
				continue;
			}
			if (!kohaJson.has(meta.kohaNativeName) || kohaJson.isNull(meta.kohaNativeName)) {
				continue;
			}
			if (meta.flags != null && meta.flags.contains(AttributeMetadata.Flags.NOT_READABLE)) {
				continue;
			}

			Object kohaNativeValue = kohaJson.get(meta.kohaNativeName);
			Object connIdValue = convertKohaValueToConnIdValue(kohaNativeValue, meta.type, meta.connIdName);

			if (connIdValue != null) {
				if (attrInfoFromSchema.isMultiValued()) { // Usar info del schema de ConnId
					// Si el valor de Koha ya es un JSONArray (ej. extended_attributes), se debe iterar
					if (kohaNativeValue instanceof JSONArray) {
						JSONArray kohaArray = (JSONArray) kohaNativeValue;
						for(int i=0; i < kohaArray.length(); i++) {
							builder.addAttribute(AttributeBuilder.build(meta.connIdName,
									convertKohaValueToConnIdValue(kohaArray.get(i), meta.type, meta.connIdName)));
						}
					} else { // Si no es JSONArray pero ConnId espera multivaluado, envolver
						builder.addAttribute(AttributeBuilder.build(meta.connIdName, Collections.singletonList(connIdValue)));
					}
				} else {
					builder.addAttribute(AttributeBuilder.build(meta.connIdName, connIdValue));
				}
			}
		}

		// Asegurar que Uid y Name se establezcan correctamente
		if (ObjectClass.ACCOUNT_NAME.equals(oci.getType())) {
			uidValue = kohaJson.optString(KOHA_PATRON_ID_NATIVE_NAME);
			AttributeMetadata accountNameMeta = metadataMap.get(ATTR_USERID);
			if (accountNameMeta != null) nameValue = kohaJson.optString(accountNameMeta.kohaNativeName);
		} else if (ObjectClass.GROUP_NAME.equals(oci.getType())) {
			uidValue = kohaJson.optString(KOHA_CATEGORY_ID_NATIVE_NAME);
			AttributeMetadata groupNameMeta = metadataMap.get(ATTR_CATEGORY_DESCRIPTION);
			if (groupNameMeta != null) nameValue = kohaJson.optString(groupNameMeta.kohaNativeName);
		}

		if (StringUtil.isBlank(uidValue)) {
			LOG.error("CONVERT_JSON: Could not determine UID for Koha object: {0}", kohaJson.toString());
			return null;
		}
		builder.setUid(uidValue);

		if (StringUtil.isNotBlank(nameValue)) {
			builder.setName(nameValue);
		} else {
			LOG.warn("CONVERT_JSON: Could not determine __NAME__ for Koha object with UID: {0}. Using UID as __NAME__.", uidValue);
			builder.setName(uidValue);
		}

		return builder.build();
	}

	private Object convertKohaValueToConnIdValue(Object kohaValue, Class<?> connIdType, String connIdAttrName) {
		if (kohaValue == null || JSONObject.NULL.equals(kohaValue)) {
			return null;
		}
		try {
			if (connIdType.equals(String.class)) {
				AttributeMetadata meta = KOHA_PATRON_ATTRIBUTE_METADATA.get(connIdAttrName);
				if (meta == null) meta = KOHA_CATEGORY_ATTRIBUTE_METADATA.get(connIdAttrName);

				if (meta != null && meta.kohaNativeName != null) {
					// Validar/Formatear fechas si el tipo ConnId es String pero el campo semánticamente es fecha
					if (meta.kohaNativeName.equals(ATTR_DATE_OF_BIRTH) || meta.kohaNativeName.equals(ATTR_EXPIRY_DATE) ||
							meta.kohaNativeName.equals(ATTR_DATE_ENROLLED) || meta.kohaNativeName.equals(ATTR_DATE_RENEWED)) {
						return LocalDate.parse(kohaValue.toString(), KOHA_DATE_FORMATTER).toString();
					} else if (meta.kohaNativeName.equals(ATTR_UPDATED_ON) || meta.kohaNativeName.equals(ATTR_LAST_SEEN)) {
						// Devolver como String en formato ISO para consistencia, ya que el tipo ConnId es String
						return ZonedDateTime.parse(kohaValue.toString(), KOHA_DATETIME_FORMATTER).toString();
					}
				}
				return kohaValue.toString();
			} else if (connIdType.equals(Boolean.class)) {
				if (kohaValue instanceof Boolean) return kohaValue;
				String kohaStr = kohaValue.toString().toLowerCase();
				return "true".equals(kohaStr) || "1".equals(kohaStr) || "yes".equals(kohaStr);
			} else if (connIdType.equals(Integer.class)) {
				if (kohaValue instanceof Integer) return kohaValue;
				return Integer.parseInt(kohaValue.toString());
			} else if (connIdType.equals(Long.class)) {
				if (kohaValue instanceof Long) return kohaValue;
				return Long.parseLong(kohaValue.toString());
			}
		} catch (NumberFormatException | DateTimeParseException e) {
			LOG.warn("CONVERT_KOHA_VALUE: Parse error for value '{0}' (attr: {1}) to type '{2}'. Error: {3}",
					kohaValue, connIdAttrName, connIdType.getSimpleName(), e.getMessage());
			return null;
		}
		return kohaValue.toString(); // Fallback
	}

	//region Métodos HTTP y Autenticación (Basic Auth por ahora)
	private void addAuthHeader(HttpRequestBase request) {
		// TODO: Implementar lógica de obtención y uso de token OAuth2 cuando RestUsersConfiguration lo soporte.
		String username = getConfiguration().getUsername();
		GuardedString guardedPassword = getConfiguration().getPassword();
		if (StringUtil.isNotBlank(username) && guardedPassword != null) {
			final StringBuilder passwordBuilder = new StringBuilder();
			guardedPassword.access(passwordBuilder::append);
			String password = passwordBuilder.toString();
			String auth = username + ":" + password;
			byte[] encodedAuth = Base64.getEncoder().encode(auth.getBytes(StandardCharsets.UTF_8));
			request.setHeader("Authorization", "Basic " + new String(encodedAuth, StandardCharsets.UTF_8));
		} else {
			LOG.warn("AUTH: Username/password for Basic Auth not configured. Request will be anonymous if API allows.");
		}
	}

	private JSONObject callRequest(HttpEntityEnclosingRequestBase request, JSONObject payload) {
		LOG.ok("HTTP_CALL_ENTITY: URI={0}", request.getURI());
		request.setHeader("Content-Type", "application/json");
		request.setHeader("Accept", "application/json");
		addAuthHeader(request);
		if (payload != null) {
			request.setEntity(new ByteArrayEntity(StringUtils.getBytesUtf8(payload.toString())));
		}
		try (CloseableHttpResponse response = execute(request)) {
			processResponseErrors(response);
			String result = EntityUtils.toString(response.getEntity());
			LOG.ok("HTTP_CALL_ENTITY: Response Body={0}", result);
			if (StringUtil.isBlank(result)) return new JSONObject();
			return new JSONObject(result);
		} catch (IOException e) {
			throw new ConnectorIOException("HTTP call (with entity) failed: " + e.getMessage(), e);
		} catch (JSONException e) {
			throw new ConnectorException("JSON parsing of response (with entity) failed: " + e.getMessage(), e);
		}
	}

	private String callRequest(HttpRequestBase request) throws IOException {
		LOG.ok("HTTP_CALL_NO_ENTITY: URI={0}", request.getURI());
		request.setHeader("Accept", "application/json");
		addAuthHeader(request);
		try (CloseableHttpResponse response = execute(request)) {
			processResponseErrors(response);
			String result = EntityUtils.toString(response.getEntity());
			LOG.ok("HTTP_CALL_NO_ENTITY: Response Body={0}", result);
			return result;
		}
	}

	@Override
	public void processResponseErrors(CloseableHttpResponse response) throws ConnectorIOException { // Cambiado de protected a public
		int statusCode = response.getStatusLine().getStatusCode();
		if (statusCode >= 200 && statusCode < 300) return;
		String responseBody = null;
		HttpEntity entity = response.getEntity();
		if (entity != null) {
			try {
				responseBody = EntityUtils.toString(entity);
			} catch (IOException e) {
				LOG.warn("Cannot read response body for error {0}: {1}", statusCode, e.getMessage());
			}
		}
		String message = "HTTP error " + statusCode + " " + response.getStatusLine().getReasonPhrase() +
				(responseBody != null ? " : " + responseBody : "");
		LOG.error("HTTP_ERROR: {0}", message);
		try { response.close(); } catch (IOException e) { LOG.warn("Failed to close error response: {0}", e.getMessage());}
		switch (statusCode) {
			case 400: throw new InvalidAttributeValueException(message);
			case 401: case 403: throw new PermissionDeniedException(message);
			case 404: throw new UnknownUidException(message);
			case 408: throw new OperationTimeoutException(message);
			case 409: throw new AlreadyExistsException(message);
			case 412: throw new PreconditionFailedException(message);
			default: throw new ConnectorIOException(message);
		}
	}
	//endregion

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