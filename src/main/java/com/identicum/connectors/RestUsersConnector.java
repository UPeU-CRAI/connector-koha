package com.identicum.connectors;

import java.io.IOException;
import java.util.List;
import java.util.Set;

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
import java.nio.charset.StandardCharsets;
import java.util.Base64;


import com.evolveum.polygon.rest.AbstractRestConnector;


@ConnectorClass(displayNameKey = "connector.identicum.rest.display", configurationClass = RestUsersConfiguration.class)
public class RestUsersConnector 
	extends AbstractRestConnector<RestUsersConfiguration> 
	implements CreateOp, UpdateOp, SchemaOp, SearchOp<RestUsersFilter>, DeleteOp, UpdateAttributeValuesOp, TestOp, TestApiOp
{
	private static final Log LOG = Log.getLog(RestUsersConnector.class);

	private static final String PATRONS_ENDPOINT = "/api/v1/patrons";
	private static final String ROLES_ENDPOINT = "/api/v1/patron_categories";

	// Atributos estándar de usuario (según Koha)
	public static final String ATTR_USERNAME = "userid";
	public static final String ATTR_FIRSTNAME = "firstname";
	public static final String ATTR_SURNAME = "surname";
	public static final String ATTR_OTHERNAMES = "othernames";
	public static final String ATTR_EMAIL = "email";
	public static final String ATTR_EMAILPRO = "emailpro";
	public static final String ATTR_PHONE = "phone";
	public static final String ATTR_USERID = "userid";
	public static final String ATTR_CARDNUMBER = "cardnumber";
	public static final String ATTR_CATEGORYCODE = "categorycode";
	public static final String ATTR_DATEEXPIRY = "dateexpiry";
	public static final String ATTR_SEX = "sex";
	public static final String ATTR_DATEOFBIRTH = "dateofbirth";
	public static final String ATTR_SORT1 = "sort1";
	public static final String ATTR_SORT2 = "sort2";
	public static final String ATTR_ADDRESS = "address";
	public static final String ATTR_CITY = "city";
	public static final String ATTR_STATE = "state";
	public static final String ATTR_ZIPCODE = "zipcode";
	public static final String ATTR_COUNTRY = "country";
	public static final String ATTR_ROLES = "roles";


	@Override
	public Schema schema() {
		LOG.ok("Reading schema");
		SchemaBuilder schemaBuilder = new SchemaBuilder(RestUsersConnector.class);
		ObjectClassInfoBuilder accountBuilder = new ObjectClassInfoBuilder();
		accountBuilder.setType(ObjectClass.ACCOUNT_NAME);

		// Identificadores obligatorios
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder("userid").setRequired(true).build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder("cardnumber").setRequired(true).build());

		// Datos personales
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder("firstname").setRequired(true).build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder("surname").setRequired(true).build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder("othernames").build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder("sex").build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder("dateofbirth").build());

		// Contacto
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder("email").build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder("emailpro").build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder("phone").build());

		// Dirección
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder("address").build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder("city").build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder("state").build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder("zipcode").build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder("country").build());

		// Clasificación académica
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder("sort1").build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder("sort2").build());

		// Expiración y categoría
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder("dateexpiry").build());
		accountBuilder.addAttributeInfo(new AttributeInfoBuilder("categorycode").setRequired(true).build());

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

	@Override
	public Uid create(ObjectClass objectClass, Set<Attribute> attributes, OperationOptions operationOptions) {
		LOG.ok("Entering create with objectClass: {0}", objectClass.toString());
		JSONObject response;
		JSONObject jo = new JSONObject();

		if (!ObjectClass.ACCOUNT.is(objectClass.getObjectClassValue())) {
			throw new ConnectorException("Unsupported object class: " + objectClass.getObjectClassValue());
		}

		// Construir el JSON que espera Koha usando constantes
		jo.put(ATTR_USERID, getStringAttr(attributes, ATTR_USERID));
		jo.put(ATTR_SURNAME, getStringAttr(attributes, ATTR_SURNAME));
		jo.put(ATTR_FIRSTNAME, getStringAttr(attributes, ATTR_FIRSTNAME));
		jo.put(ATTR_EMAIL, getStringAttr(attributes, ATTR_EMAIL));
		jo.put(ATTR_EMAILPRO, getStringAttr(attributes, ATTR_EMAILPRO));
		jo.put(ATTR_CARDNUMBER, getStringAttr(attributes, ATTR_CARDNUMBER));
		jo.put(ATTR_CATEGORYCODE, getStringAttr(attributes, ATTR_CATEGORYCODE));
		jo.put(ATTR_DATEEXPIRY, getStringAttr(attributes, ATTR_DATEEXPIRY));
		jo.put(ATTR_PHONE, getStringAttr(attributes, ATTR_PHONE));
		jo.put(ATTR_SEX, getStringAttr(attributes, ATTR_SEX));
		jo.put(ATTR_OTHERNAMES, getStringAttr(attributes, ATTR_OTHERNAMES));
		jo.put(ATTR_ADDRESS, getStringAttr(attributes, ATTR_ADDRESS));
		jo.put(ATTR_CITY, getStringAttr(attributes, ATTR_CITY));
		jo.put(ATTR_STATE, getStringAttr(attributes, ATTR_STATE));
		jo.put(ATTR_ZIPCODE, getStringAttr(attributes, ATTR_ZIPCODE));
		jo.put(ATTR_COUNTRY, getStringAttr(attributes, ATTR_COUNTRY));
		jo.put(ATTR_SORT1, getStringAttr(attributes, ATTR_SORT1));
		jo.put(ATTR_SORT2, getStringAttr(attributes, ATTR_SORT2));
		jo.put(ATTR_DATEOFBIRTH, getStringAttr(attributes, ATTR_DATEOFBIRTH));

		LOG.info("JSON to send to Koha: {0}", jo.toString());

		// Construir endpoint
		String endpoint = getConfiguration().getServiceAddress() + PATRONS_ENDPOINT;
		HttpEntityEnclosingRequestBase request = new HttpPost(endpoint);
		response = callRequest(request, jo);

		// Koha devuelve el patron ID como respuesta
		String newUid = response.has("patron_id") ? response.get("patron_id").toString() :
				response.has("cardnumber") ? response.get("cardnumber").toString() :
						response.has("userid") ? response.get("userid").toString() :
								null;

		if (newUid == null) {
			throw new ConnectorException("Unable to extract UID from Koha response: " + response.toString());
		}

		LOG.info("Created Koha patron, UID: {0}", newUid);
		return new Uid(newUid);
	}

	@Override
	public Uid update(ObjectClass objectClass, Uid uid, Set<Attribute> attributes, OperationOptions operationOptions) {
		LOG.ok("Entering update with objectClass: {0}", objectClass.toString());

		if (!ObjectClass.ACCOUNT.is(objectClass.getObjectClassValue())) {
			throw new ConnectorException("Unsupported object class: " + objectClass.getObjectClassValue());
		}

		JSONObject jo = new JSONObject();

		// Lista blanca de atributos permitidos
		Set<String> allowedAttrs = Set.of(
				ATTR_USERID, ATTR_SURNAME, ATTR_FIRSTNAME, ATTR_EMAIL, ATTR_EMAILPRO,
				ATTR_CARDNUMBER, ATTR_CATEGORYCODE, ATTR_DATEEXPIRY, ATTR_PHONE,
				ATTR_SEX, ATTR_OTHERNAMES, ATTR_ADDRESS, ATTR_CITY, ATTR_STATE,
				ATTR_ZIPCODE, ATTR_COUNTRY, ATTR_SORT1, ATTR_SORT2, ATTR_DATEOFBIRTH
		);

		for (Attribute attribute : attributes) {
			String attrName = attribute.getName();
			if (allowedAttrs.contains(attrName)) {
				LOG.info("Update - Atributo recibido {0}: {1}", attrName, attribute.getValue());
				jo.put(attrName, getStringAttr(attributes, attrName));
			} else {
				LOG.warn("Atributo no permitido ignorado: {0}", attrName);
			}
		}

		LOG.info("JSON delta to send to Koha: {0}", jo.toString());

		String endpoint = getConfiguration().getServiceAddress() + PATRONS_ENDPOINT + "/" + uid.getUidValue();

		try {
			HttpEntityEnclosingRequestBase request = new HttpPatch(endpoint);
			JSONObject response = callRequest(request, jo);

			String newUid = response.has("patron_id") ? response.get("patron_id").toString() :
					response.has("cardnumber") ? response.get("cardnumber").toString() :
							response.has("userid") ? response.get("userid").toString() :
									uid.getUidValue();

			LOG.info("Updated Koha patron, UID: {0}", newUid);
			return new Uid(newUid);

		} catch (Exception e) {
			throw new RuntimeException("Error actualizando usuario en Koha", e);
		}
	}


	@Override
	public Uid addAttributeValues(ObjectClass objectClass, Uid uid, Set<Attribute> attributes, OperationOptions operationOptions) {
		LOG.ok("Entering addValue with objectClass: {0}", objectClass.toString());
		try {
			for (Attribute attribute : attributes) {
				LOG.info("AddAttributeValue - Atributo recibido {0}: {1}", attribute.getName(), attribute.getValue());
				if (RestUsersConnector.ATTR_ROLES.equals(attribute.getName())) {
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
				if (RestUsersConnector.ATTR_ROLES.equals(attribute.getName())) {
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

	private void authHeader(HttpRequestBase request) {
		String username = getConfiguration().getUsername();
		GuardedString guardedPassword = getConfiguration().getPassword();

		if (username != null && guardedPassword != null) {
			final StringBuilder passwordBuilder = new StringBuilder();

			// Desencriptar la contraseña de forma segura
			guardedPassword.access(clearChars -> passwordBuilder.append(clearChars));

			String password = passwordBuilder.toString();
			String auth = username + ":" + password;
			byte[] encodedAuth = java.util.Base64.getEncoder().encode(auth.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
		JSONObject root = new JSONObject(responseStr);

		if (!root.has("patrons")) {
			LOG.error("Respuesta inesperada de Koha: no contiene el array 'patrons'");
			throw new ConnectorException("Respuesta inesperada: falta 'patrons'");
		}

		JSONArray patrons = root.getJSONArray("patrons");
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
		addIfPresent(builder, "cardnumber", user);
		addIfPresent(builder, "userid", user);
		addIfPresent(builder, "surname", user);
		addIfPresent(builder, "firstname", user);
		addIfPresent(builder, "othernames", user);
		addIfPresent(builder, "email", user);
		addIfPresent(builder, "emailpro", user);
		addIfPresent(builder, "phone", user);
		addIfPresent(builder, "categorycode", user);
		addIfPresent(builder, "dateexpiry", user);
		addIfPresent(builder, "sex", user);
		addIfPresent(builder, "dateofbirth", user);
		addIfPresent(builder, "sort1", user);
		addIfPresent(builder, "sort2", user);
		addIfPresent(builder, "address", user);
		addIfPresent(builder, "city", user);
		addIfPresent(builder, "state", user);
		addIfPresent(builder, "zipcode", user);
		addIfPresent(builder, "country", user);

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

		builder.setUid(new Uid(role.getString("categorycode")));
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