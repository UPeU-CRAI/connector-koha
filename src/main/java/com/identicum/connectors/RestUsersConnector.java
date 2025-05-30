package com.identicum.connectors;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.Base64;

import org.apache.commons.codec.binary.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPatch;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.util.EntityUtils;
import org.identityconnectors.common.StringUtil;
import org.identityconnectors.common.logging.Log;
import org.identityconnectors.framework.api.operations.TestApiOp;
import org.identityconnectors.framework.common.exceptions.AlreadyExistsException;
import org.identityconnectors.framework.common.exceptions.ConnectorException;
import org.identityconnectors.framework.common.exceptions.ConnectorIOException;
import org.identityconnectors.framework.common.exceptions.OperationTimeoutException;
import org.identityconnectors.framework.common.exceptions.PermissionDeniedException;
import org.identityconnectors.framework.common.exceptions.PreconditionFailedException;
import org.identityconnectors.framework.common.exceptions.UnknownUidException;
import org.identityconnectors.framework.common.objects.Attribute;
import org.identityconnectors.framework.common.objects.AttributeInfoBuilder;
import org.identityconnectors.framework.common.objects.ConnectorObject;
import org.identityconnectors.framework.common.objects.ConnectorObjectBuilder;
import org.identityconnectors.framework.common.objects.ObjectClass;
import org.identityconnectors.framework.common.objects.ObjectClassInfoBuilder;
import org.identityconnectors.framework.common.objects.OperationOptions;
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
import org.identityconnectors.framework.spi.operations.UpdateAttributeValuesOp;
import org.identityconnectors.framework.spi.operations.UpdateOp;
import org.json.JSONArray;
import org.json.JSONObject;
import org.identityconnectors.framework.common.objects.Name;
import org.identityconnectors.common.security.GuardedString;


import com.evolveum.polygon.rest.AbstractRestConnector;


@ConnectorClass(displayNameKey = "connector.identicum.rest.display", configurationClass = RestUsersConfiguration.class)
public class RestUsersConnector
		extends AbstractRestConnector<RestUsersConfiguration>
		implements CreateOp, UpdateOp, SchemaOp, SearchOp<RestUsersFilter>, DeleteOp, UpdateAttributeValuesOp, TestOp, TestApiOp {

	// ==========================================================
	// 🔹 Configuración estática y atributos estándar del conector
	// ==========================================================
	private static final Log LOG = Log.getLog(RestUsersConnector.class);

	private static final String PATRONS_ENDPOINT = "/api/v1/patrons";
	private static final String ROLES_ENDPOINT = "/api/v1/patron_categories";

	// Atributos estándar de usuario (según API de Koha)
	public static final String ATTR_USERID = "userid";
	public static final String ATTR_FIRSTNAME = "firstname";
	public static final String ATTR_SURNAME = "surname";
	public static final String ATTR_OTHER_NAME = "other_name";
	public static final String ATTR_EMAIL = "email";
	public static final String ATTR_PHONE = "phone";
	public static final String ATTR_MOBILE = "mobile";
	public static final String ATTR_SECONDARY_EMAIL = "secondary_email";
	public static final String ATTR_SECONDARY_PHONE = "secondary_phone";
	public static final String ATTR_SMS_NUMBER = "sms_number";
	public static final String ATTR_SMS_PROVIDER_ID = "sms_provider_id";

	public static final String ATTR_CARDNUMBER = "cardnumber";
	public static final String ATTR_CATEGORY_ID = "category_id";
	public static final String ATTR_EXPIRY_DATE = "expiry_date";
	public static final String ATTR_GENDER = "gender";
	public static final String ATTR_DATE_OF_BIRTH = "date_of_birth";
	public static final String ATTR_STATISTICS_1 = "statistics_1";
	public static final String ATTR_STATISTICS_2 = "statistics_2";

	public static final String ATTR_ADDRESS = "address";
	public static final String ATTR_ADDRESS2 = "address2";
	public static final String ATTR_CITY = "city";
	public static final String ATTR_STATE = "state";
	public static final String ATTR_ZIPCODE = "zipcode"; // zip legacy
	public static final String ATTR_POSTAL_CODE = "postal_code"; // preferido
	public static final String ATTR_COUNTRY = "country";
	public static final String ATTR_LIBRARY_ID = "library_id";

	public static final String ATTR_OPAC_NOTES = "opac_notes";
	public static final String ATTR_STAFF_NOTES = "staff_notes";

	// Direcciones alternativas
	public static final String ATTR_ALTADDRESS_ADDRESS = "altaddress_address";
	public static final String ATTR_ALTADDRESS_ADDRESS2 = "altaddress_address2";
	public static final String ATTR_ALTADDRESS_CITY = "altaddress_city";
	public static final String ATTR_ALTADDRESS_STATE = "altaddress_state";
	public static final String ATTR_ALTADDRESS_POSTAL_CODE = "altaddress_postal_code";
	public static final String ATTR_ALTADDRESS_COUNTRY = "altaddress_country";
	public static final String ATTR_ALTADDRESS_PHONE = "altaddress_phone";
	public static final String ATTR_ALTADDRESS_EMAIL = "altaddress_email";

	// Contacto alternativo
	public static final String ATTR_ALTCONTACT_FIRSTNAME = "altcontact_firstname";
	public static final String ATTR_ALTCONTACT_SURNAME = "altcontact_surname";
	public static final String ATTR_ALTCONTACT_PHONE = "altcontact_phone";
	public static final String ATTR_ALTCONTACT_ADDRESS = "altcontact_address";
	public static final String ATTR_ALTCONTACT_ADDRESS2 = "altcontact_address2";
	public static final String ATTR_ALTCONTACT_CITY = "altcontact_city";
	public static final String ATTR_ALTCONTACT_STATE = "altcontact_state";
	public static final String ATTR_ALTCONTACT_COUNTRY = "altcontact_country";
	public static final String ATTR_ALTCONTACT_POSTAL_CODE = "altcontact_postal_code";

	// Otros
	public static final String ATTR_PROTECTED = "protected";
	public static final String ATTR_ANONYMIZED = "anonymized";
	public static final String ATTR_PRIVACY = "privacy";
	public static final String ATTR_PATRON_CARD_LOST = "patron_card_lost";

	public static final String ATTR_ROLES = "roles";

	// Atributos permitidos que pueden ser enviados a Koha
	private static final Set<String> ALLOWED_USER_ATTRIBUTES = Set.of(
			ATTR_USERID, ATTR_FIRSTNAME, ATTR_SURNAME, ATTR_OTHER_NAME,
			ATTR_EMAIL, ATTR_PHONE, ATTR_MOBILE,
			ATTR_SECONDARY_EMAIL, ATTR_SECONDARY_PHONE,
			ATTR_SMS_NUMBER, ATTR_SMS_PROVIDER_ID,
			ATTR_CARDNUMBER, ATTR_CATEGORY_ID, ATTR_EXPIRY_DATE,
			ATTR_GENDER, ATTR_DATE_OF_BIRTH, ATTR_STATISTICS_1, ATTR_STATISTICS_2,
			ATTR_ADDRESS, ATTR_ADDRESS2, ATTR_CITY, ATTR_STATE,
			ATTR_ZIPCODE, ATTR_POSTAL_CODE, ATTR_COUNTRY,
			ATTR_LIBRARY_ID, ATTR_OPAC_NOTES, ATTR_STAFF_NOTES,
			ATTR_ALTADDRESS_ADDRESS, ATTR_ALTADDRESS_ADDRESS2,
			ATTR_ALTADDRESS_CITY, ATTR_ALTADDRESS_STATE,
			ATTR_ALTADDRESS_POSTAL_CODE, ATTR_ALTADDRESS_COUNTRY,
			ATTR_ALTADDRESS_PHONE, ATTR_ALTADDRESS_EMAIL,
			ATTR_ALTCONTACT_FIRSTNAME, ATTR_ALTCONTACT_SURNAME,
			ATTR_ALTCONTACT_PHONE, ATTR_ALTCONTACT_ADDRESS,
			ATTR_ALTCONTACT_ADDRESS2, ATTR_ALTCONTACT_CITY,
			ATTR_ALTCONTACT_STATE, ATTR_ALTCONTACT_COUNTRY,
			ATTR_ALTCONTACT_POSTAL_CODE,
			ATTR_PROTECTED, ATTR_ANONYMIZED, ATTR_PRIVACY, ATTR_PATRON_CARD_LOST
	);



	private JSONObject buildUserJson(Set<Attribute> attributes, Set<String> allowedAttrs) {
		JSONObject jo = new JSONObject();

		for (Attribute attr : attributes) {
			String name = attr.getName();

			if (!allowedAttrs.contains(name)) {
				LOG.warn("Atributo no permitido ignorado: {0}", name);
				continue;
			}

			List<Object> values = attr.getValue();

			// Manejo de valores nulos o vacíos
			if (values == null || values.isEmpty()) {
				LOG.info("Atributo {0} está vacío, se omite", name);
				continue;
			}

			// Si el atributo tiene un solo valor, usar directamente
			if (values.size() == 1) {
				jo.put(name, values.get(0));
			} else {
				// Si tiene múltiples valores, agregar como arreglo JSON
				jo.put(name, new JSONArray(values));
			}

			LOG.info("Procesado {0}: {1}", name, values);
		}

		return jo;
	}

	// Extrae el valor en formato String del atributo especificado si está presente
	@Override
	protected String getStringAttr(Set<Attribute> attributes, String name) {
		for (Attribute attr : attributes) {
			if (name.equals(attr.getName()) && attr.getValue() != null && !attr.getValue().isEmpty()) {
				Object value = attr.getValue().get(0);
				return value != null ? value.toString() : null;
			}
		}
		return null;
	}



	// ====================================================
	// 🔹 Operaciones de búsqueda y definición de esquema
	// ====================================================
	@Override
	public Schema schema() {
		LOG.ok("Reading schema");
		SchemaBuilder schemaBuilder = new SchemaBuilder(RestUsersConnector.class);
		ObjectClassInfoBuilder accountBuilder = new ObjectClassInfoBuilder();
		accountBuilder.setType(ObjectClass.ACCOUNT_NAME);

		// Identificadores obligatorios
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder(ATTR_USERID).setRequired(true).build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder(ATTR_CARDNUMBER).setRequired(true).build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder(ATTR_LIBRARY_ID).setRequired(true).build());

		// Datos personales
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder(ATTR_FIRSTNAME).setRequired(true).build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder(ATTR_SURNAME).setRequired(true).build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder(ATTR_OTHER_NAME).build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder(ATTR_GENDER).build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder(ATTR_DATE_OF_BIRTH).build());

		// Contacto
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder(ATTR_EMAIL).build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder(ATTR_PHONE).build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder(ATTR_MOBILE).build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder(ATTR_SECONDARY_EMAIL).build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder(ATTR_SECONDARY_PHONE).build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder(ATTR_SMS_NUMBER).build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder(ATTR_SMS_PROVIDER_ID).build());

		// Dirección
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder(ATTR_ADDRESS).build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder(ATTR_ADDRESS2).build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder(ATTR_CITY).build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder(ATTR_STATE).build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder(ATTR_ZIPCODE).build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder(ATTR_POSTAL_CODE).build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder(ATTR_COUNTRY).build());

		// Clasificación académica
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder(ATTR_STATISTICS_1).build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder(ATTR_STATISTICS_2).build());

		// Expiración y categoría
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder(ATTR_EXPIRY_DATE).build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder(ATTR_CATEGORY_ID).setRequired(true).build());

		// Notas
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder(ATTR_OPAC_NOTES).build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder(ATTR_STAFF_NOTES).build());

		// Direcciones alternativas
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder(ATTR_ALTADDRESS_ADDRESS).build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder(ATTR_ALTADDRESS_ADDRESS2).build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder(ATTR_ALTADDRESS_CITY).build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder(ATTR_ALTADDRESS_STATE).build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder(ATTR_ALTADDRESS_POSTAL_CODE).build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder(ATTR_ALTADDRESS_COUNTRY).build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder(ATTR_ALTADDRESS_PHONE).build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder(ATTR_ALTADDRESS_EMAIL).build());

		// Contacto alternativo
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder(ATTR_ALTCONTACT_FIRSTNAME).build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder(ATTR_ALTCONTACT_SURNAME).build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder(ATTR_ALTCONTACT_PHONE).build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder(ATTR_ALTCONTACT_ADDRESS).build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder(ATTR_ALTCONTACT_ADDRESS2).build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder(ATTR_ALTCONTACT_CITY).build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder(ATTR_ALTCONTACT_STATE).build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder(ATTR_ALTCONTACT_COUNTRY).build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder(ATTR_ALTCONTACT_POSTAL_CODE).build());

		// Otros
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder(ATTR_PROTECTED).build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder(ATTR_ANONYMIZED).build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder(ATTR_PRIVACY).build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder(ATTR_PATRON_CARD_LOST).build());

		// UID y nombre (necesario para ConnId)
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder(Name.NAME).setRequired(true).build());

		schemaBuilder.defineObjectClass(accountBuilder.build());

		// Objeto tipo grupo (opcional, depende de si Koha los usa)
		ObjectClassInfoBuilder groupBuilder = new ObjectClassInfoBuilder();
		groupBuilder.setType(ObjectClass.GROUP_NAME);
		schemaBuilder.defineObjectClass(groupBuilder.build());

		LOG.ok("Exiting schema");
		return schemaBuilder.build();
	}



	// ====================================
	// 🔹 Operaciones CRUD (usuario Koha)
	// ====================================
	@Override
	public Uid create(ObjectClass objectClass, Set<Attribute> attributes, OperationOptions operationOptions) {
		LOG.ok("Entering create with objectClass: {0}", objectClass);

		// Solo soporta creación de cuentas de usuario
		if (!ObjectClass.ACCOUNT.is(objectClass.getObjectClassValue())) {
			throw new ConnectorException("Unsupported object class: " + objectClass.getObjectClassValue());
		}

		// Validación de atributos obligatorios
		validateRequiredAttributes(attributes, ATTR_USERID, ATTR_CARDNUMBER, ATTR_LIBRARY_ID);

		// Construir JSON con los atributos permitidos
		JSONObject jo = buildUserJson(attributes, ALLOWED_USER_ATTRIBUTES);
		LOG.info("JSON to send to Koha: {0}", jo.toString());

		// Construir y enviar la petición POST
		String endpoint = getConfiguration().getServiceAddress() + PATRONS_ENDPOINT;
		HttpEntityEnclosingRequestBase request = new HttpPost(endpoint);
		JSONObject response = callRequest(request, jo);

		// Obtener el UID del usuario creado desde la respuesta
		String newUid = response.optString("patron_id",
				response.optString("cardnumber",
						response.optString("userid", null)
				)
		);

		if (newUid == null) {
			throw new ConnectorException("Unable to extract UID from Koha response: " + response.toString());
		}

		LOG.info("Created Koha patron, UID: {0}", newUid);
		return new Uid(newUid);
	}
	private void validateRequiredAttributes(Set<Attribute> attributes, String... requiredNames) {
		for (String name : requiredNames) {
			if (getStringAttr(attributes, name) == null) {
				throw new ConnectorException("Missing required attribute: " + name);
			}
		}
	}



	@Override
	public Uid update(ObjectClass objectClass, Uid uid, Set<Attribute> attributes, OperationOptions operationOptions) {
		LOG.ok("Entering update with objectClass: {0}, UID: {1}", objectClass, uid);

		// Validar tipo de objeto
		if (!ObjectClass.ACCOUNT.is(objectClass.getObjectClassValue())) {
			throw new ConnectorException("Unsupported object class: " + objectClass.getObjectClassValue());
		}

		// Validar que el UID esté presente
		if (uid == null || uid.getUidValue() == null || uid.getUidValue().isEmpty()) {
			throw new ConnectorException("UID is required for update operation.");
		}

		// Generar JSON con los atributos permitidos
		JSONObject jo = buildUserJson(attributes, ALLOWED_USER_ATTRIBUTES);
		LOG.info("JSON delta to send to Koha: {0}", jo.toString());

		// Construir endpoint
		String endpoint = getConfiguration().getServiceAddress() + PATRONS_ENDPOINT + "/" + uid.getUidValue();

		try {
			HttpEntityEnclosingRequestBase request = new HttpPatch(endpoint);
			JSONObject response = callRequest(request, jo);

			// Obtener UID actualizado desde la respuesta
			String updatedUid = response.optString("patron_id",
					response.optString("cardnumber",
							response.optString("userid", uid.getUidValue())
					)
			);

			LOG.info("Updated Koha patron, UID: {0}", updatedUid);
			return new Uid(updatedUid);

		} catch (Exception e) {
			throw new ConnectorException("Error actualizando usuario en Koha (UID: " + uid.getUidValue() + ")", e);
		}
	}



	@Override
	public Uid addAttributeValues(ObjectClass objectClass, Uid uid, Set<Attribute> attributes, OperationOptions operationOptions) {
		LOG.ok("Entering addValue with objectClass: {0}", objectClass.toString());
		try {
			for (Attribute attribute : attributes) {
				LOG.info("AddAttributeValue - Atributo recibido {0}: {1}", attribute.getName(), attribute.getValue());
				if (ATTR_ROLES.equals(attribute.getName())) {
					List<Object> addedRoles = attribute.getValue();
					for (Object role : addedRoles) {
						JSONObject json = new JSONObject();
						json.put("id", role.toString());

						String endpoint = String.format("%s%s/%s/%s", getConfiguration().getServiceAddress(), PATRONS_ENDPOINT, uid.getUidValue(), ROLES_ENDPOINT);
						LOG.info("Adding role {0} for user {1} on endpoint {2}", role, uid.getUidValue(), endpoint);
						HttpEntityEnclosingRequestBase request = new HttpPost(endpoint);
						callRequest(request, json);
					}
				}
			}
		} catch (Exception io) {
			throw new RuntimeException("Error modificando usuario por rest", io);
		}
		return uid;
	}

	@Override
	public Uid removeAttributeValues(ObjectClass objectClass, Uid uid, Set<Attribute> attributes, OperationOptions operationOptions) {
		LOG.ok("Entering removeValue with objectClass: {0}", objectClass.toString());
		try {
			for (Attribute attribute : attributes) {
				LOG.info("RemoveAttributeValue - Atributo recibido {0}: {1}", attribute.getName(), attribute.getValue());
				if (ATTR_ROLES.equals(attribute.getName())) {
					List<Object> revokedRoles = attribute.getValue();
					for (Object role : revokedRoles) {
						String endpoint = String.format("%s/%s/%s/%s/%s", getConfiguration().getServiceAddress(), PATRONS_ENDPOINT, uid.getUidValue(), ROLES_ENDPOINT, role.toString());
						LOG.info("Revoking role {0} for user {1} on endpoint {2}", role, uid.getUidValue(), endpoint);
						HttpDelete request = new HttpDelete(endpoint);
						callRequest(request);
					}
				}
			}
		} catch (Exception io) {
			throw new RuntimeException("Error modificando usuario por rest", io);
		}
		return uid;
	}

	// ========================================
	// 🔹 Utilitarios HTTP y helpers comunes
	// ========================================
	private void authHeader(HttpRequestBase request) {
		String username = getConfiguration().getUsername();
		GuardedString guardedPassword = getConfiguration().getPassword();

		if (username != null && guardedPassword != null) {
			final StringBuilder passwordBuilder = new StringBuilder();

			// Desencriptar la contraseña de forma segura
			guardedPassword.access(clearChars -> passwordBuilder.append(clearChars));

			String password = passwordBuilder.toString();
			String auth = username + ":" + password;
			byte[] encodedAuth = Base64.getEncoder().encode(auth.getBytes(StandardCharsets.UTF_8));
			String authHeader = "Basic " + new String(encodedAuth);

			request.setHeader("Authorization", authHeader);
		} else {
			LOG.warn("Username or password is null, cannot set Authorization header.");
		}
	}

	protected JSONObject callRequest(HttpEntityEnclosingRequestBase request, JSONObject jo) //throws IOException
	{
		// don't log request here - password field !!!
		LOG.ok("Request URI: {0}", request.getURI());
		LOG.ok("Request body: {0}", jo.toString());
		request.setHeader("Content-Type", "application/json");

		authHeader(request);

		
		HttpEntity entity = new ByteArrayEntity(StringUtils.getBytesUtf8(jo.toString()));
		request.setEntity(entity);
		CloseableHttpResponse response = execute(request);
		LOG.ok("response: {0}", response);

		this.processResponseErrors(response);
		// processDrupalResponseErrors(response);

		String result;
		try
		{
			result = EntityUtils.toString(response.getEntity());
		}
		catch(IOException io)
		{
			throw new ConnectorException("Error reading api response.", io);
		}
		LOG.ok("response body: {0}", result);
		closeResponse(response);
		return new JSONObject(result);
	}

	protected String callRequest(HttpRequestBase request) throws IOException {
		LOG.ok("request URI: {0}", request.getURI());
		request.setHeader("Content-Type", "application/json");

		// ✅ Agrega la autenticación aquí
		authHeader(request);

		CloseableHttpResponse response = execute(request);
		LOG.ok("response: {0}", response);

		super.processResponseErrors(response);

		String result = EntityUtils.toString(response.getEntity());
		LOG.ok("response body: {0}", result);
		closeResponse(response);
		return result;
	}

	
	public void processResponseErrors(CloseableHttpResponse response) {
        int statusCode = response.getStatusLine().getStatusCode();
        if (statusCode >= 200 && statusCode <= 299) {
            return;
        }
        String responseBody = null;
        try {
            responseBody = EntityUtils.toString(response.getEntity());
        } catch (IOException e) {
            LOG.warn("cannot read response body: " + e, e);
        }

        String message = "HTTP error " + statusCode + " " + response.getStatusLine().getReasonPhrase() + " : " + responseBody;
        LOG.error("{0}", message);
        if (statusCode == 400 || statusCode == 405 || statusCode == 406) {
            closeResponse(response);
            throw new ConnectorIOException(message);
        }
        if (statusCode == 401 || statusCode == 402 || statusCode == 403 || statusCode == 407) {
            closeResponse(response);
            throw new PermissionDeniedException(message);
        }
        if (statusCode == 404 || statusCode == 410) {
            closeResponse(response);
            throw new UnknownUidException(message);
        }
        if (statusCode == 408) {
            closeResponse(response);
            throw new OperationTimeoutException(message);
        }
        if (statusCode == 409) {
            closeResponse(response);
            throw new AlreadyExistsException();
        }
        if (statusCode == 412) {
            closeResponse(response);
            throw new PreconditionFailedException(message);
        }
        if (statusCode == 418) {
            closeResponse(response);
            throw new UnsupportedOperationException("Sorry, no cofee: " + message);
        }
        // TODO: other codes
        closeResponse(response);
        throw new ConnectorException(message);
    }

	@Override
	public FilterTranslator<RestUsersFilter> createFilterTranslator(ObjectClass arg0, OperationOptions arg1)
	{
		return new RestUsersFilterTranslator();
	}

	@Override
	public void executeQuery(ObjectClass objectClass, RestUsersFilter query, ResultsHandler handler, OperationOptions options) {
		try {
			LOG.info("executeQuery on {0}, query: {1}, options: {2}", objectClass, query, options);

			if (objectClass.is(ObjectClass.ACCOUNT_NAME)) {
				String baseUrl = getConfiguration().getServiceAddress() + PATRONS_ENDPOINT;

				if (query != null && query.byUid != null) {
					String endpoint = baseUrl + "/" + query.byUid;
					HttpGet request = new HttpGet(endpoint);
					JSONObject response = new JSONObject(callRequest(request));

					ConnectorObject connectorObject = convertUserToConnectorObject(response);
					LOG.info("Calling handler.handle on single object of AccountObjectClass");
					handler.handle(connectorObject);
					LOG.info("Called handler.handle on single object of AccountObjectClass");

				} else {
					String filters = "";
					if (query != null && StringUtil.isNotBlank(query.byUsername)) {
						filters = "?q=userid:" + query.byUsername;
					}

					HttpGet request = new HttpGet(baseUrl + filters);
					LOG.info("Calling handleUsers for multiple objects of AccountObjectClass");
					handleUsers(request, handler, options, false);
					LOG.info("Called handleUsers for multiple objects of AccountObjectClass");
				}

			} else if (objectClass.is(ObjectClass.GROUP_NAME)) {
				String baseUrl = getConfiguration().getServiceAddress() + ROLES_ENDPOINT;

				if (query != null && query.byUid != null) {
					HttpGet request = new HttpGet(baseUrl + "/" + query.byUid);
					JSONObject response = new JSONObject(callRequest(request));

					ConnectorObject connectorObject = convertRoleToConnectorObject(response);
					handler.handle(connectorObject);

				} else {
					String filters = "";
					if (query != null && StringUtil.isNotBlank(query.byName)) {
						filters = "?q=description:" + query.byName;
					}

					HttpGet request = new HttpGet(baseUrl + filters);
					handleRoles(request, handler, options, false);
				}

			} else {
				throw new ConnectorException("Unsupported object class for query: " + objectClass.getObjectClassValue());
			}

		} catch (IOException e) {
			LOG.error("Error querying objects on Koha REST API", e);
			throw new RuntimeException(e.getMessage(), e);
		}
	}


	private boolean handleUsers(HttpGet request, ResultsHandler handler, OperationOptions options, boolean findAll) throws IOException {
		String responseStr = callRequest(request);

		// Koha devuelve un array JSON directo, no un objeto con clave 'patrons'
		JSONArray patrons;
		try {
			patrons = new JSONArray(responseStr);
		} catch (Exception e) {
			LOG.error("Respuesta inesperada de Koha: no es un array JSON válido", e);
			throw new ConnectorException("La respuesta de Koha no es un array JSON válido", e);
		}

		LOG.ok("Number of patrons retrieved: {0}", patrons.length());

		for (int i = 0; i < patrons.length(); i++) {
			JSONObject user = patrons.getJSONObject(i);
			ConnectorObject connectorObject = convertUserToConnectorObject(user);
			LOG.info("Calling handler.handle inside loop. Iteration #{0}", i);
			boolean finish = !handler.handle(connectorObject);
			if (finish) {
				return true; // early termination
			}
		}

		return false;
	}


	// ===========================
	// 🔹 Conversión de objetos
	// ===========================
	private ConnectorObject convertUserToConnectorObject(JSONObject user) {
		ConnectorObjectBuilder builder = new ConnectorObjectBuilder();

		// UID (puede ser patron_id, cardnumber o userid)
		String uid = user.has("patron_id") ? user.get("patron_id").toString() :
				user.has("cardnumber") ? user.get("cardnumber").toString() :
						user.has("userid") ? user.get("userid").toString() : null;

		if (uid == null) {
			throw new ConnectorException("No se pudo determinar UID para el usuario Koha: " + user.toString());
		}

		builder.setUid(new Uid(uid));

		// Name lógico (MidPoint requiere este campo)
		if (user.has("userid")) {
			builder.setName(user.getString("userid"));
		}

		// Atributos principales
		addIfPresent(builder, ATTR_CARDNUMBER, user);
		addIfPresent(builder, ATTR_USERID, user);
		addIfPresent(builder, ATTR_SURNAME, user);
		addIfPresent(builder, ATTR_FIRSTNAME, user);
		addIfPresent(builder, ATTR_OTHER_NAME, user);
		addIfPresent(builder, ATTR_EMAIL, user);
		addIfPresent(builder, ATTR_PHONE, user);
		addIfPresent(builder, ATTR_CATEGORY_ID, user);
		addIfPresent(builder, ATTR_EXPIRY_DATE, user);
		addIfPresent(builder, ATTR_GENDER, user);
		addIfPresent(builder, ATTR_DATE_OF_BIRTH, user);
		addIfPresent(builder, ATTR_STATISTICS_1, user);
		addIfPresent(builder, ATTR_STATISTICS_2, user);
		addIfPresent(builder, ATTR_ADDRESS, user);
		addIfPresent(builder, ATTR_CITY, user);
		addIfPresent(builder, ATTR_STATE, user);
		addIfPresent(builder, ATTR_ZIPCODE, user);
		addIfPresent(builder, ATTR_COUNTRY, user);

		ConnectorObject connectorObject = builder.build();
		LOG.ok("convertUserToConnectorObject → UID: {0}, object: {1}", uid, connectorObject);
		return connectorObject;
	}

	private void addIfPresent(ConnectorObjectBuilder builder, String attrName, JSONObject json) {
		if (json.has(attrName) && !json.isNull(attrName)) {
			builder.addAttribute(attrName, json.get(attrName).toString());
		}
	}

	
	private boolean handleRoles(HttpGet request, ResultsHandler handler, OperationOptions options, boolean findAll) throws IOException 
	{
        JSONArray users = new JSONArray(callRequest(request));
        LOG.ok("Number of roles: {0}", users.length());

        for (int i = 0; i < users.length(); i++) 
        {
			// only basic fields
			JSONObject user = users.getJSONObject(i);
			ConnectorObject connectorObject = convertRoleToConnectorObject(user);
			boolean finish = !handler.handle(connectorObject);
			if (finish) {
			    return true;
			}
        }
        return false;
    }

	private ConnectorObject convertRoleToConnectorObject(JSONObject role) throws IOException {
		ConnectorObjectBuilder builder = new ConnectorObjectBuilder(); // 💥 ESTA LÍNEA FALTABA

		builder.setUid(new Uid(role.getString("category_id")));
		builder.setName(role.getString("description"));

		addIfPresent(builder, "enrolmentperiod", role);
		addIfPresent(builder, "datecreated", role);

		ConnectorObject connectorObject = builder.build();
		LOG.ok("convertRoleToConnectorObject, user: {0}, \n\tconnectorObject: {1}", role.get("id").toString(), connectorObject);
		return connectorObject;
	}


	@Override
	public void delete(ObjectClass objectClass, Uid uid, OperationOptions options) {
		LOG.ok("Entering delete with objectClass: {0}, uid: {1}", objectClass.getObjectClassValue(), uid.getUidValue());

		if (!ObjectClass.ACCOUNT.is(objectClass.getObjectClassValue())) {
			throw new ConnectorException("Unsupported object class for deletion: " + objectClass.getObjectClassValue());
		}

		try {
			String endpoint = getConfiguration().getServiceAddress() + PATRONS_ENDPOINT + "/" + uid.getUidValue();
			HttpDelete deleteReq = new HttpDelete(endpoint);
			callRequest(deleteReq);
			LOG.info("Deleted Koha patron with UID: {0}", uid.getUidValue());
		} catch (Exception ex) {
			LOG.error("Error deleting user in Koha", ex);
			throw new ConnectorException("Error deleting user in Koha: " + ex.getMessage(), ex);
		}
	}

	// ======================
	// 🔹 Test de conectividad
	// ======================
	@Override
	public void test()
	{
		LOG.info("Entering test");
		try
		{
			HttpGet request = new HttpGet(getConfiguration().getServiceAddress() + PATRONS_ENDPOINT);
			callRequest(request);
			LOG.info("Test OK");
		}
		catch (Exception io)
		{
			LOG.error("Error testing connector", io);
			throw new RuntimeException("Error testing endpoint", io);
		}
	}

}