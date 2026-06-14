package com.identicum.connectors.mappers;

import com.identicum.connectors.model.AttributeMetadata;
import org.identityconnectors.common.StringUtil;
import org.identityconnectors.framework.common.objects.Attribute;
import org.identityconnectors.framework.common.objects.AttributeBuilder;
import org.identityconnectors.framework.common.objects.ConnectorObject;
import org.identityconnectors.framework.common.objects.ConnectorObjectBuilder;
import org.identityconnectors.framework.common.objects.Name;
import org.identityconnectors.framework.common.objects.ObjectClass;
import org.identityconnectors.framework.common.objects.Uid;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.json.JSONException;

import org.identityconnectors.common.logging.Log;
import org.identityconnectors.framework.common.objects.OperationalAttributes;

/**
 * Mapper especializado para los objetos de tipo Patrón (Account).
 * Contiene las definiciones de atributos de patrones y la lógica para su transformación.
 */
public class PatronMapper extends BaseMapper {
    private static final Log LOG = Log.getLog(PatronMapper.class);

    // --- Definiciones de Atributos de PATRONES ---
    public static final String KOHA_PATRON_ID_NATIVE_NAME = "patron_id";
    public static final Map<String, AttributeMetadata> ATTRIBUTE_METADATA_MAP = new LinkedHashMap<>();

    /**
     * Atributo ConnId con los bytes de la foto del patron.
     * Se provisiona via canal JDBC a la tabla {@code patronimage} de Koha,
     * NUNCA via la API REST (Koha 25.11 no tiene endpoint de imagen).
     */
    public static final String ATTR_PHOTO = "photo";

    /**
     * Atributo ConnId con el mimetype de la foto del patron.
     * Acompana a {@link #ATTR_PHOTO} en el canal JDBC.
     */
    public static final String ATTR_PHOTO_MIMETYPE = "photo_mimetype";

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
        // Nuevas constantes de atributos
        final String ATTR_SECONDARY_EMAIL = "secondary_email";
        final String ATTR_STATISTICS_1 = "statistics_1";
        final String ATTR_STATISTICS_2 = "statistics_2";
        final String ATTR_LOGIN_ATTEMPTS = "login_attempts";
        final String ATTR_PRIVACY = "privacy";
        final String ATTR_LANG = "lang";
        final String ATTR_EXTENDED_ATTRIBUTES = "extended_attributes";

        ATTRIBUTE_METADATA_MAP.put(ATTR_USERID, new AttributeMetadata(ATTR_USERID, "userid", String.class, AttributeMetadata.Flags.REQUIRED));
        ATTRIBUTE_METADATA_MAP.put(ATTR_CARDNUMBER, new AttributeMetadata(ATTR_CARDNUMBER, "cardnumber", String.class, AttributeMetadata.Flags.REQUIRED));
        ATTRIBUTE_METADATA_MAP.put(ATTR_SURNAME, new AttributeMetadata(ATTR_SURNAME, "surname", String.class, AttributeMetadata.Flags.REQUIRED));
        ATTRIBUTE_METADATA_MAP.put(ATTR_FIRSTNAME, new AttributeMetadata(ATTR_FIRSTNAME, "firstname", String.class));
        ATTRIBUTE_METADATA_MAP.put(ATTR_EMAIL, new AttributeMetadata(ATTR_EMAIL, "email", String.class));
        ATTRIBUTE_METADATA_MAP.put(ATTR_PHONE, new AttributeMetadata(ATTR_PHONE, "phone", String.class));
        ATTRIBUTE_METADATA_MAP.put(ATTR_MOBILE, new AttributeMetadata(ATTR_MOBILE, "mobile", String.class));
        ATTRIBUTE_METADATA_MAP.put(ATTR_LIBRARY_ID, new AttributeMetadata(ATTR_LIBRARY_ID, "library_id", String.class, AttributeMetadata.Flags.REQUIRED));
        ATTRIBUTE_METADATA_MAP.put(ATTR_CATEGORY_ID, new AttributeMetadata(ATTR_CATEGORY_ID, "category_id", String.class, AttributeMetadata.Flags.REQUIRED));
        ATTRIBUTE_METADATA_MAP.put(ATTR_GENDER, new AttributeMetadata(ATTR_GENDER, "gender", String.class));
        ATTRIBUTE_METADATA_MAP.put(ATTR_ADDRESS, new AttributeMetadata(ATTR_ADDRESS, "address", String.class));
        ATTRIBUTE_METADATA_MAP.put(ATTR_ADDRESS2, new AttributeMetadata(ATTR_ADDRESS2, "address2", String.class));
        ATTRIBUTE_METADATA_MAP.put(ATTR_CITY, new AttributeMetadata(ATTR_CITY, "city", String.class));
        ATTRIBUTE_METADATA_MAP.put(ATTR_STATE, new AttributeMetadata(ATTR_STATE, "state", String.class));
        ATTRIBUTE_METADATA_MAP.put(ATTR_POSTAL_CODE, new AttributeMetadata(ATTR_POSTAL_CODE, "postal_code", String.class));
        ATTRIBUTE_METADATA_MAP.put(ATTR_COUNTRY, new AttributeMetadata(ATTR_COUNTRY, "country", String.class));
        ATTRIBUTE_METADATA_MAP.put(ATTR_STAFF_NOTES, new AttributeMetadata(ATTR_STAFF_NOTES, "staff_notes", String.class));
        ATTRIBUTE_METADATA_MAP.put(ATTR_OPAC_NOTES, new AttributeMetadata(ATTR_OPAC_NOTES, "opac_notes", String.class));
        ATTRIBUTE_METADATA_MAP.put(ATTR_DATE_OF_BIRTH, new AttributeMetadata(ATTR_DATE_OF_BIRTH, "date_of_birth", String.class));
        ATTRIBUTE_METADATA_MAP.put(ATTR_EXPIRY_DATE, new AttributeMetadata(ATTR_EXPIRY_DATE, "expiry_date", String.class));
        ATTRIBUTE_METADATA_MAP.put(ATTR_DATE_ENROLLED, new AttributeMetadata(ATTR_DATE_ENROLLED, "date_enrolled", String.class, AttributeMetadata.Flags.NOT_UPDATEABLE));
        ATTRIBUTE_METADATA_MAP.put(ATTR_DATE_RENEWED, new AttributeMetadata(ATTR_DATE_RENEWED, "date_renewed", String.class, AttributeMetadata.Flags.NOT_CREATABLE, AttributeMetadata.Flags.NOT_UPDATEABLE));
        ATTRIBUTE_METADATA_MAP.put(ATTR_INCORRECT_ADDRESS, new AttributeMetadata(ATTR_INCORRECT_ADDRESS, "incorrect_address", Boolean.class));
        ATTRIBUTE_METADATA_MAP.put(ATTR_PATRON_CARD_LOST, new AttributeMetadata(ATTR_PATRON_CARD_LOST, "patron_card_lost", Boolean.class));
        ATTRIBUTE_METADATA_MAP.put(ATTR_EXPIRED, new AttributeMetadata(ATTR_EXPIRED, "expired", Boolean.class, AttributeMetadata.Flags.NOT_CREATABLE, AttributeMetadata.Flags.NOT_UPDATEABLE));
        ATTRIBUTE_METADATA_MAP.put(ATTR_RESTRICTED, new AttributeMetadata(ATTR_RESTRICTED, "restricted", Boolean.class, AttributeMetadata.Flags.NOT_CREATABLE, AttributeMetadata.Flags.NOT_UPDATEABLE));
        ATTRIBUTE_METADATA_MAP.put(ATTR_AUTORENEW_CHECKOUTS, new AttributeMetadata(ATTR_AUTORENEW_CHECKOUTS, "autorenew_checkouts", Boolean.class));
        ATTRIBUTE_METADATA_MAP.put(ATTR_ANONYMIZED, new AttributeMetadata(ATTR_ANONYMIZED, "anonymized", Boolean.class, AttributeMetadata.Flags.NOT_CREATABLE, AttributeMetadata.Flags.NOT_UPDATEABLE));
        ATTRIBUTE_METADATA_MAP.put(ATTR_PROTECTED, new AttributeMetadata(ATTR_PROTECTED, "protected", Boolean.class));
        ATTRIBUTE_METADATA_MAP.put(ATTR_UPDATED_ON, new AttributeMetadata(ATTR_UPDATED_ON, "updated_on", String.class, AttributeMetadata.Flags.NOT_CREATABLE, AttributeMetadata.Flags.NOT_UPDATEABLE));
        ATTRIBUTE_METADATA_MAP.put(ATTR_LAST_SEEN, new AttributeMetadata(ATTR_LAST_SEEN, "last_seen", String.class, AttributeMetadata.Flags.NOT_CREATABLE, AttributeMetadata.Flags.NOT_UPDATEABLE));
        // Registro en el mapa de metadatos
        ATTRIBUTE_METADATA_MAP.put(ATTR_SECONDARY_EMAIL, new AttributeMetadata(ATTR_SECONDARY_EMAIL, "secondary_email", String.class));
        ATTRIBUTE_METADATA_MAP.put(ATTR_STATISTICS_1, new AttributeMetadata(ATTR_STATISTICS_1, "statistics_1", String.class));
        ATTRIBUTE_METADATA_MAP.put(ATTR_STATISTICS_2, new AttributeMetadata(ATTR_STATISTICS_2, "statistics_2", String.class));
        ATTRIBUTE_METADATA_MAP.put(ATTR_LOGIN_ATTEMPTS, new AttributeMetadata(ATTR_LOGIN_ATTEMPTS, "login_attempts", Integer.class));
        ATTRIBUTE_METADATA_MAP.put(ATTR_PRIVACY, new AttributeMetadata(ATTR_PRIVACY, "privacy", Integer.class));
        ATTRIBUTE_METADATA_MAP.put(ATTR_LANG, new AttributeMetadata(ATTR_LANG, "lang", String.class));

        // Nota: extended_attributes requiere un manejo especial por ser una lista de objetos en Koha
        ATTRIBUTE_METADATA_MAP.put(ATTR_EXTENDED_ATTRIBUTES, new AttributeMetadata(ATTR_EXTENDED_ATTRIBUTES, "extended_attributes", String.class, AttributeMetadata.Flags.MULTIVALUED));

        // New in Koha 25.x
        ATTRIBUTE_METADATA_MAP.put("preferred_name", new AttributeMetadata("preferred_name", "preferred_name", String.class));
        ATTRIBUTE_METADATA_MAP.put("pronouns", new AttributeMetadata("pronouns", "pronouns", String.class));
        ATTRIBUTE_METADATA_MAP.put("primary_contact_method", new AttributeMetadata("primary_contact_method", "primary_contact_method", String.class));
        ATTRIBUTE_METADATA_MAP.put("sms_number", new AttributeMetadata("sms_number", "sms_number", String.class));
        ATTRIBUTE_METADATA_MAP.put("middle_name", new AttributeMetadata("middle_name", "middle_name", String.class));
        ATTRIBUTE_METADATA_MAP.put("title", new AttributeMetadata("title", "title", String.class));
        ATTRIBUTE_METADATA_MAP.put("other_name", new AttributeMetadata("other_name", "other_name", String.class));
        ATTRIBUTE_METADATA_MAP.put("initials", new AttributeMetadata("initials", "initials", String.class));
        ATTRIBUTE_METADATA_MAP.put("relationship_type", new AttributeMetadata("relationship_type", "relationship_type", String.class));
        ATTRIBUTE_METADATA_MAP.put("sms_provider_id", new AttributeMetadata("sms_provider_id", "sms_provider_id", Integer.class));

        // Alternate address fields
        ATTRIBUTE_METADATA_MAP.put("altaddress_address", new AttributeMetadata("altaddress_address", "altaddress_address", String.class));
        ATTRIBUTE_METADATA_MAP.put("altaddress_city", new AttributeMetadata("altaddress_city", "altaddress_city", String.class));
        ATTRIBUTE_METADATA_MAP.put("altaddress_state", new AttributeMetadata("altaddress_state", "altaddress_state", String.class));
        ATTRIBUTE_METADATA_MAP.put("altaddress_postal_code", new AttributeMetadata("altaddress_postal_code", "altaddress_postal_code", String.class));
        ATTRIBUTE_METADATA_MAP.put("altaddress_country", new AttributeMetadata("altaddress_country", "altaddress_country", String.class));
        ATTRIBUTE_METADATA_MAP.put("altaddress_email", new AttributeMetadata("altaddress_email", "altaddress_email", String.class));
        ATTRIBUTE_METADATA_MAP.put("altaddress_phone", new AttributeMetadata("altaddress_phone", "altaddress_phone", String.class));

        // --- Foto del patron (canal JDBC, NO REST) ---
        // returnedByDefault=false: la foto solo se lee si MidPoint la pide
        // explicitamente; nunca se hace un SELECT de blob por fila en busquedas masivas.
        // Estos dos atributos se EXCLUYEN del payload JSON REST en buildPatronJson()
        // y se procesan en una rama JDBC dedicada del conector.
        ATTRIBUTE_METADATA_MAP.put(ATTR_PHOTO,
                new AttributeMetadata(ATTR_PHOTO, "photo", byte[].class, AttributeMetadata.Flags.NOT_RETURNED_BY_DEFAULT));
        ATTRIBUTE_METADATA_MAP.put(ATTR_PHOTO_MIMETYPE,
                new AttributeMetadata(ATTR_PHOTO_MIMETYPE, "photo_mimetype", String.class, AttributeMetadata.Flags.NOT_RETURNED_BY_DEFAULT));
    }

    /**
     * Conjunto ALLOWLIST de campos nativos de Koha que el conector puede ENVIAR en el
     * cuerpo de un PUT /patrons/{id}. Se deriva de {@link #ATTRIBUTE_METADATA_MAP}:
     * incluye todo campo conocido por el conector que sea actualizable
     * ({@code !isNotUpdateable()}), EXCLUYE los canales no-REST (photo / photo_mimetype)
     * y EXCLUYE extended_attributes (van por su endpoint dedicado).
     *
     * <p>MOTIVO (v1.3.12) — fix del HTTP 500 determinista en UPDATE: el GET de un patron
     * en Koha 24.05+/25.05/26.05 devuelve campos CALCULADOS que NO pertenecen al schema
     * escribible (p.ej. {@code self_renewal_available}). El schema patron.yaml declara
     * {@code additionalProperties: false}, por lo que reenviar CUALQUIER campo ajeno al
     * schema en el PUT provoca {@code Koha::Exceptions::Object::PropertyNotFound}
     * (&lt;&lt;No property X for Koha::Patron&gt;&gt;) -> HTTP 500. El antiguo enfoque de
     * read-modify-write con DENYLIST de campos read-only era fragil: cada nuevo campo
     * calculado que Koha agregue al GET rompe el PUT. Una ALLOWLIST construida desde el
     * propio mapeo del conector es robusta ante cambios de version de Koha.</p>
     */
    public static java.util.Set<String> getWritablePatronNativeFields() {
        java.util.Set<String> writable = new HashSet<>();
        for (AttributeMetadata meta : ATTRIBUTE_METADATA_MAP.values()) {
            if (meta.isNotUpdateable()) {
                continue;
            }
            String nativeName = meta.getKohaNativeName();
            // Canales que NO viajan por REST / van por endpoint dedicado.
            if ("photo".equals(nativeName) || "photo_mimetype".equals(nativeName)
                    || "extended_attributes".equals(nativeName)) {
                continue;
            }
            writable.add(nativeName);
        }
        return writable;
    }

    /**
     * Construye un objeto JSON para un Patrón a partir de atributos de ConnId.
     */
    public JSONObject buildPatronJson(Set<Attribute> attributes, boolean isCreate) {
        LOG.ok("Construyendo JSON de Patrón para {0}. Atributos ConnId: {1}", (isCreate ? "CREATE" : "UPDATE"), attributes);
        JSONObject jo = new JSONObject();
        Set<String> processedKohaAttrs = new HashSet<>();
        final String nameAttribute = "userid"; // ConnId Name attribute for Patrons

        for (Attribute attr : attributes) {
            String connIdAttrName = attr.getName();
            if (Uid.NAME.equals(connIdAttrName)) {
                LOG.ok("Skipping Uid.NAME attribute");
                continue;
            }

            // La foto del patron NO viaja por REST: se provisiona via canal JDBC
            // (tabla patronimage). El conector la procesa en una rama dedicada.
            if (ATTR_PHOTO.equals(connIdAttrName) || ATTR_PHOTO_MIMETYPE.equals(connIdAttrName)) {
                LOG.ok("Excluyendo del payload REST el atributo de foto '{0}' (se maneja via JDBC).", connIdAttrName);
                continue;
            }

            AttributeMetadata meta = Name.NAME.equals(connIdAttrName) ?
                    ATTRIBUTE_METADATA_MAP.get(nameAttribute) : ATTRIBUTE_METADATA_MAP.get(connIdAttrName);

            if (meta == null) {
                LOG.warn("No hay metadatos para el atributo de Patrón '{0}'. Omitiendo.", connIdAttrName);
                continue;
            }

            if (!isCreate && meta.isNotUpdateable()) {
                LOG.ok("Skipping non-updateable: {0}", connIdAttrName);
                continue;
            }
            if (isCreate && meta.isNotCreatable()) {
                LOG.ok("Skipping non-creatable: {0}", connIdAttrName);
                continue;
            }

            // Special handling for extended_attributes
            if ("extended_attributes".equals(meta.getConnIdName())) {
                List<Object> values = attr.getValue();
                if (values != null && !values.isEmpty()) {
                    jo.put("extended_attributes", convertExtendedAttributesToKoha(values));
                    processedKohaAttrs.add("extended_attributes");
                }
                continue;
            }

            List<Object> values = attr.getValue();
            if (values == null || values.isEmpty() || values.get(0) == null) {
                if (!isCreate) { // Only set to NULL for updates
                    LOG.ok("Setting {0} to NULL", meta.getKohaNativeName());
                    jo.put(meta.getKohaNativeName(), JSONObject.NULL);
                }
                continue;
            }

            // Avoid processing the same Koha native attribute name multiple times if ConnId attributes map to it
            // (e.g. Name and a specific username attribute mapping to the same Koha field)
            if (processedKohaAttrs.contains(meta.getKohaNativeName())) {
                continue;
            }

            Object kohaValue = meta.isMultivalued() ?
                    new JSONArray(values.stream().map(v -> convertConnIdValueToKohaJsonValue(v, meta)).filter(Objects::nonNull).toArray())
                    : convertConnIdValueToKohaJsonValue(values.get(0), meta);

            jo.put(meta.getKohaNativeName(), kohaValue);
            processedKohaAttrs.add(meta.getKohaNativeName());
        }
        LOG.ok("Patron JSON built with {0} fields", jo.length());
        return jo;
    }

    /**
     * --- Lógica para evitar el atributo duplicado ---
     * Convierte un objeto JSON de un Patrón en un ConnectorObject.
     */
    public ConnectorObject convertJsonToPatronObject(JSONObject kohaJson) {
        LOG.ok("Converting Koha JSON to ConnectorObject, fields: {0}", kohaJson.length());
        if (kohaJson == null) {
            LOG.warn("El JSON de Koha proporcionado es nulo. Retornando nulo.");
            return null;
        }

        ConnectorObjectBuilder builder = new ConnectorObjectBuilder().setObjectClass(ObjectClass.ACCOUNT);

        // UID es mandatorio. Koha usa 'patron_id'.
        Object rawUid = kohaJson.opt(KOHA_PATRON_ID_NATIVE_NAME);
        String uidVal = (rawUid != null) ? String.valueOf(rawUid) : null;

        if (StringUtil.isBlank(uidVal)) {
            LOG.error("Patrón JSON no tiene UID ({0}). JSON: {1}", KOHA_PATRON_ID_NATIVE_NAME, kohaJson.toString(2));
            return null; // No se puede construir un ConnectorObject sin UID
        }
        builder.setUid(new Uid(uidVal));

        // Name attribute - ConnId 'Name' (que mapea a 'userid' de Koha)
        AttributeMetadata nameAttributeMeta = ATTRIBUTE_METADATA_MAP.get("userid");
        String nameVal = null;
        if (nameAttributeMeta != null && kohaJson.has(nameAttributeMeta.getKohaNativeName())) {
            nameVal = kohaJson.optString(nameAttributeMeta.getKohaNativeName(), null);
        }
        builder.setName(new Name(nameVal != null ? nameVal : uidVal)); // Fallback a UID si 'userid' no está o es nulo.

        // Resto de los atributos
        for (AttributeMetadata meta : ATTRIBUTE_METADATA_MAP.values()) {
            // Omitir el que ya se usó para Name.NAME si su ConnIdName es "userid"
            if ("userid".equals(meta.getConnIdName())) {
                continue;
            }

            // La foto del patron no proviene del JSON REST; la lee el conector
            // via canal JDBC solo cuando MidPoint la solicita explicitamente.
            if (ATTR_PHOTO.equals(meta.getConnIdName()) || ATTR_PHOTO_MIMETYPE.equals(meta.getConnIdName())) {
                continue;
            }

            // Special handling for extended_attributes.
            //
            // CRITICAL (v1.3.11) — habilitacion del reconcile/backfill:
            // Cuando el patron se obtiene con el embed (x-koha-embed: extended_attributes),
            // el conector SIEMPRE debe exponer el atributo 'extended_attributes' en el
            // ConnectorObject, INCLUSO si la lista esta vacia. Si el atributo se omite,
            // MidPoint lo marca como "incompleto" (no fully-fetched) y SUPRIME los deltas
            // aditivos de valores tolerados durante el reconcile -> el backfill de STUDYCYCLE
            // (y de cualquier extended_attribute) NUNCA se computa para borrowers existentes.
            // Emitir el atributo (aunque vacio) lo marca "completo" y permite que MidPoint
            // calcule el add y lo envie en el UPDATE.
            //
            // La presencia del embed se infiere de que la clave exista en el JSON. El embed
            // se solicita en TODAS las lecturas de patron (getPatron, findPatronExact y la
            // busqueda paginada), por lo que un patron real siempre trae la clave; un GET sin
            // embed (no usado en el flujo de reconcile) simplemente no la trae y se omite.
            if ("extended_attributes".equals(meta.getConnIdName())) {
                if (kohaJson.has("extended_attributes")) {
                    Object raw = kohaJson.get("extended_attributes");
                    List<String> converted = (raw instanceof JSONArray)
                            ? convertExtendedAttributesFromKoha((JSONArray) raw)
                            : new ArrayList<>();
                    // Emitir SIEMPRE el atributo cuando el embed estuvo presente, aunque vacio.
                    builder.addAttribute(AttributeBuilder.build("extended_attributes", converted));
                }
                continue;
            }

            if (meta.isNotReadable() || !kohaJson.has(meta.getKohaNativeName()) || kohaJson.isNull(meta.getKohaNativeName())) {
                continue;
            }

            Object kohaNativeVal = kohaJson.get(meta.getKohaNativeName());
            Object connIdVal = convertKohaValueToConnIdValue(kohaNativeVal, meta);

            if (connIdVal != null) {
                builder.addAttribute(AttributeBuilder.build(meta.getConnIdName(), connIdVal));
            }
        }
        builder.addAttribute(OperationalAttributes.ENABLE_NAME, computeEnabled(kohaJson));
        ConnectorObject resultObject = builder.build();
        LOG.ok("Patron ConnectorObject built: {0}", resultObject.getUid());
        return resultObject;
    }

    /**
     * Applies the __ENABLE__ operational attribute to a Koha patron JSON payload.
     * Dual mechanism: patron_card_lost for immediate block, expiry_date for temporal.
     */
    public void applyEnableAttribute(JSONObject payload, Boolean enabled) {
        if (enabled == null) return;
        if (!enabled) {
            payload.put("patron_card_lost", true);
            payload.put("expiry_date", java.time.LocalDate.now().minusDays(1).toString());
        } else {
            payload.put("patron_card_lost", false);
            // expiry_date is NOT cleared here — managed by separate mapping
        }
    }

    /**
     * Computes the __ENABLE__ state from Koha patron JSON.
     * Returns false if patron_card_lost=true OR expired=true.
     */
    public boolean computeEnabled(JSONObject kohaJson) {
        boolean cardLost = kohaJson.optBoolean("patron_card_lost", false);
        boolean expired = kohaJson.optBoolean("expired", false);
        return !cardLost && !expired;
    }

    /**
     * Converts Koha extended_attributes JSONArray to ConnId multivalued String list.
     * Each element is a JSON string: {"type":"X","value":"Y"}
     */
    private List<String> convertExtendedAttributesFromKoha(JSONArray kohaAttrs) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < kohaAttrs.length(); i++) {
            JSONObject attr = kohaAttrs.getJSONObject(i);
            JSONObject normalized = new JSONObject();
            normalized.put("type", attr.getString("type"));
            normalized.put("value", attr.optString("value", ""));
            result.add(normalized.toString());
        }
        return result;
    }

    /**
     * Extrae y convierte el atributo ConnId {@code extended_attributes} (multivaluado,
     * lista de strings JSON {@code {"type":...,"value":...}}) a un JSONArray apto para
     * el endpoint dedicado de Koha {@code PUT /patrons/{id}/extended_attributes}.
     *
     * <p>Se usa en UPDATE (v1.3.11): el endpoint principal PUT /patrons/{id} no acepta
     * extended_attributes, por lo que el conector los escribe por canal aparte. Devuelve
     * {@code null} si el atributo no esta presente en el conjunto (no hay nada que escribir);
     * devuelve un array (posiblemente vacio) si el atributo esta presente.</p>
     *
     * @param attributes atributos ConnId de la operacion UPDATE
     * @return JSONArray de {@code {"type":...,"value":...}}, o {@code null} si el atributo no vino
     */
    public JSONArray extractExtendedAttributesArray(Set<Attribute> attributes) {
        if (attributes == null) {
            return null;
        }
        for (Attribute attr : attributes) {
            if ("extended_attributes".equals(attr.getName())) {
                List<Object> values = attr.getValue();
                if (values == null) {
                    return new JSONArray();
                }
                return convertExtendedAttributesToKoha(values);
            }
        }
        return null;
    }

    /**
     * Converts ConnId multivalued String list to Koha extended_attributes JSONArray.
     * Each String element must be a valid JSON: {"type":"X","value":"Y"}
     */
    private JSONArray convertExtendedAttributesToKoha(List<Object> connIdValues) {
        JSONArray result = new JSONArray();
        for (Object val : connIdValues) {
            if (val == null) continue;
            try {
                JSONObject parsed = new JSONObject(val.toString());
                if (parsed.has("type")) {
                    result.put(parsed);
                }
            } catch (JSONException e) {
                LOG.warn("Skipping invalid extended_attribute value: {0}", val);
            }
        }
        return result;
    }
}
