package com.identicum.connectors.mappers;

import com.identicum.connectors.model.AttributeMetadata;
import org.identityconnectors.common.StringUtil;
import org.identityconnectors.framework.common.objects.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;

import org.identityconnectors.common.logging.Log;

/**
 * Mapper especializado para los objetos de tipo Patrón (Account).
 * Contiene las definiciones de atributos de patrones y la lógica para su transformación.
 */
public class PatronMapper extends BaseMapper {
    private static final Log LOG = Log.getLog(PatronMapper.class);

    // --- Definiciones de Atributos de PATRONES ---
    public static final String KOHA_PATRON_ID_NATIVE_NAME = "patron_id";
    public static final Map<String, AttributeMetadata> ATTRIBUTE_METADATA_MAP = new LinkedHashMap<>();

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
        ATTRIBUTE_METADATA_MAP.put(ATTR_DATE_ENROLLED, new AttributeMetadata(ATTR_DATE_ENROLLED, "date_enrolled", String.class, AttributeMetadata.Flags.NOT_CREATABLE, AttributeMetadata.Flags.NOT_UPDATEABLE));
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
                LOG.ok("Trace Mapper: Omitiendo atributo Uid.NAME ({0}) directamente, se maneja por separado.", connIdAttrName);
                continue;
            }

            AttributeMetadata meta = Name.NAME.equals(connIdAttrName) ?
                    ATTRIBUTE_METADATA_MAP.get(nameAttribute) : ATTRIBUTE_METADATA_MAP.get(connIdAttrName);
            LOG.ok("Trace Mapper: Procesando atributo ConnId: {0}, Meta: {1}", connIdAttrName, meta);

            if (meta == null) {
                LOG.warn("No hay metadatos para el atributo de Patrón '{0}'. Omitiendo.", connIdAttrName);
                continue;
            }

            if (!isCreate && meta.isNotUpdateable()) {
                LOG.ok("Trace Mapper: Omitiendo atributo {0} debido a que es no actualizable", connIdAttrName);
                continue;
            }
            if (isCreate && meta.isNotCreatable()) {
                LOG.ok("Trace Mapper: Omitiendo atributo {0} debido a que es no creable", connIdAttrName);
                continue;
            }

            List<Object> values = attr.getValue();
            if (values == null || values.isEmpty() || values.get(0) == null) {
                if (!isCreate) { // Only set to NULL for updates
                    LOG.ok("Trace Mapper: Estableciendo {0} a NULL en JSON para UPDATE", meta.getKohaNativeName());
                    jo.put(meta.getKohaNativeName(), JSONObject.NULL);
                } else {
                    LOG.ok("Trace Mapper: Omitiendo atributo {0} con valor nulo/vacío para CREATE.", connIdAttrName);
                }
                continue;
            }

            // Avoid processing the same Koha native attribute name multiple times if ConnId attributes map to it
            // (e.g. Name and a specific username attribute mapping to the same Koha field)
            if (processedKohaAttrs.contains(meta.getKohaNativeName())) {
                LOG.ok("Trace Mapper: Atributo Koha nativo '{0}' ya procesado (posiblemente por Name y userid mapeando al mismo). Omitiendo para ConnId '{1}'.", meta.getKohaNativeName(), connIdAttrName);
                continue;
            }

            Object kohaValue = meta.isMultivalued() ?
                    new JSONArray(values.stream().map(v -> convertConnIdValueToKohaJsonValue(v, meta)).filter(Objects::nonNull).toArray())
                    : convertConnIdValueToKohaJsonValue(values.get(0), meta);

            LOG.ok("Trace Mapper: Mapeando ConnId '{0}' a Koha '{1}' con valor: {2}", connIdAttrName, meta.getKohaNativeName(), kohaValue);
            jo.put(meta.getKohaNativeName(), kohaValue);
            processedKohaAttrs.add(meta.getKohaNativeName());
        }
        LOG.ok("JSON de Patrón construido: {0}", jo.toString(2));
        return jo;
    }

    /**
     * --- Lógica para evitar el atributo duplicado ---
     * Convierte un objeto JSON de un Patrón en un ConnectorObject.
     */
    public ConnectorObject convertJsonToPatronObject(JSONObject kohaJson) {
        LOG.ok("Convirtiendo JSON de Koha a ConnectorObject (Patrón): {0}", kohaJson.toString(2));
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
            LOG.ok("Trace Mapper: Mapeando Koha '{0}' a ConnId Name con valor: {1}", nameAttributeMeta.getKohaNativeName(), nameVal);
        } else {
            LOG.ok("Trace Mapper: Atributo Koha para Name (mapeado de 'userid') no encontrado o nulo en JSON. Usando UID como Name fallback.");
        }
        builder.setName(new Name(nameVal != null ? nameVal : uidVal)); // Fallback a UID si 'userid' no está o es nulo.

        // Resto de los atributos
        for (AttributeMetadata meta : ATTRIBUTE_METADATA_MAP.values()) {
            LOG.ok("Trace Mapper: Considerando atributo Koha '{0}' (ConnId: '{1}')", meta.getKohaNativeName(), meta.getConnIdName());

            // Omitir el que ya se usó para Name.NAME si su ConnIdName es "userid"
            if ("userid".equals(meta.getConnIdName())) {
                LOG.ok("Trace Mapper: Omitiendo el procesamiento explícito de '{0}' como atributo regular, ya manejado como Name.NAME.", meta.getConnIdName());
                continue;
            }

            if (meta.isNotReadable() || !kohaJson.has(meta.getKohaNativeName()) || kohaJson.isNull(meta.getKohaNativeName())) {
                LOG.ok("Trace Mapper: Omitiendo atributo Koha '{0}' porque no es leíble o no está presente/nulo en el JSON.", meta.getKohaNativeName());
                continue;
            }

            Object kohaNativeVal = kohaJson.get(meta.getKohaNativeName());
            Object connIdVal = convertKohaValueToConnIdValue(kohaNativeVal, meta);

            if (connIdVal != null) {
                LOG.ok("Trace Mapper: Mapeando Koha '{0}' a ConnId '{1}' con valor: {2}", meta.getKohaNativeName(), meta.getConnIdName(), connIdVal);
                builder.addAttribute(AttributeBuilder.build(meta.getConnIdName(), connIdVal));
            } else {
                LOG.ok("Trace Mapper: Valor convertido para ConnId '{0}' (desde Koha '{1}') es nulo. No se añadirá.", meta.getConnIdName(), meta.getKohaNativeName());
            }
        }
        ConnectorObject resultObject = builder.build();
        LOG.ok("ConnectorObject (Patrón) construido: {0}", resultObject.toString()); // toString() de ConnectorObject es informativo.
        return resultObject;
    }
}