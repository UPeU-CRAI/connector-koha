package com.identicum.connectors.mappers;

import com.identicum.connectors.model.AttributeMetadata;
import org.identityconnectors.common.StringUtil;
import org.identityconnectors.framework.common.objects.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;

/**
 * Mapper especializado para los objetos de tipo Patrón (Account).
 * Contiene las definiciones de atributos de patrones y la lógica para su transformación.
 */
public class PatronMapper extends BaseMapper {

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
        JSONObject jo = new JSONObject();
        Set<String> processedKohaAttrs = new HashSet<>();
        final String nameAttribute = "userid";

        for (Attribute attr : attributes) {
            String connIdAttrName = attr.getName();
            if (Uid.NAME.equals(connIdAttrName)) continue;

            AttributeMetadata meta = Name.NAME.equals(connIdAttrName) ?
                    ATTRIBUTE_METADATA_MAP.get(nameAttribute) : ATTRIBUTE_METADATA_MAP.get(connIdAttrName);

            if (meta == null) {
                LOG.warn("BUILD_JSON: No hay metadatos para el atributo de Patrón '{0}'. Omitiendo.", connIdAttrName);
                continue;
            }

            // Esta lógica es la que permite la flexibilidad.
            // Si el atributo está marcado como no actualizable, se omite.
            if (!isCreate && meta.isNotUpdateable()) continue;
            if (isCreate && meta.isNotCreatable()) continue;

            List<Object> values = attr.getValue();
            // Esta lógica permite "borrar" un campo enviando un nulo.
            if (values == null || values.isEmpty() || values.get(0) == null) {
                if (!isCreate) jo.put(meta.getKohaNativeName(), JSONObject.NULL);
                continue;
            }
            if (processedKohaAttrs.contains(meta.getKohaNativeName())) continue;

            Object kohaValue = meta.isMultivalued() ?
                    new JSONArray(values.stream().map(v -> convertConnIdValueToKohaJsonValue(v, meta)).filter(Objects::nonNull).toArray())
                    : convertConnIdValueToKohaJsonValue(values.get(0), meta);

            jo.put(meta.getKohaNativeName(), kohaValue);
            processedKohaAttrs.add(meta.getKohaNativeName());
        }
        return jo;
    }

    /**
     * --- Lógica para evitar el atributo duplicado ---
     * Convierte un objeto JSON de un Patrón en un ConnectorObject.
     */
    public ConnectorObject convertJsonToPatronObject(JSONObject kohaJson) {
        if (kohaJson == null) return null;

        ConnectorObjectBuilder builder = new ConnectorObjectBuilder().setObjectClass(ObjectClass.ACCOUNT);

        String uidVal = String.valueOf(kohaJson.get(KOHA_PATRON_ID_NATIVE_NAME)); // Lectura segura
        String nameVal = kohaJson.optString(ATTRIBUTE_METADATA_MAP.get("userid").getKohaNativeName(), null);

        if (StringUtil.isBlank(uidVal)) {
            LOG.error("CONVERT_JSON: Se encontró un Patrón sin UID. Se omitirá. JSON: {0}", kohaJson.toString());
            return null;
        }

        builder.setUid(new Uid(uidVal));
        builder.setName(new Name(nameVal != null ? nameVal : uidVal));

        for (AttributeMetadata meta : ATTRIBUTE_METADATA_MAP.values()) {
            if ("userid".equals(meta.getConnIdName())) {
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
        return builder.build();
    }
}