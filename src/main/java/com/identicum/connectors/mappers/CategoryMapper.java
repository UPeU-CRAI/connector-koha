package com.identicum.connectors.mappers;

import com.identicum.connectors.model.AttributeMetadata;
import org.identityconnectors.common.StringUtil;
import org.identityconnectors.framework.common.objects.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;

/**
 * Mapper especializado para los objetos de tipo Categoría de Patrón (Group).
 * Contiene las definiciones de atributos de categorías y la lógica para su transformación.
 */
public class CategoryMapper extends BaseMapper {

    // --- Definiciones de Atributos de CATEGORÍAS ---
    public static final String KOHA_CATEGORY_ID_NATIVE_NAME = "patron_category_id";
    public static final Map<String, AttributeMetadata> ATTRIBUTE_METADATA_MAP = new LinkedHashMap<>();

    static {
        final String ATTR_CATEGORY_NAME = "name";
        final String ATTR_CATEGORY_TYPE = "category_type";
        final String ATTR_CATEGORY_ENROLMENT_PERIOD = "enrolment_period";
        final String ATTR_CATEGORY_MIN_PASS_LENGTH = "min_password_length";
        final String ATTR_CATEGORY_STRONG_PASS = "require_strong_password";
        final String ATTR_CATEGORY_UPPER_AGE_LIMIT = "upper_age_limit";
        final String ATTR_CATEGORY_LOWER_AGE_LIMIT = "lower_age_limit";

        // La API de Koha es un poco inconsistente. Para las categorías, el nombre se llama 'description'.
        ATTRIBUTE_METADATA_MAP.put(ATTR_CATEGORY_NAME, new AttributeMetadata(ATTR_CATEGORY_NAME, "description", String.class, AttributeMetadata.Flags.REQUIRED));
        ATTRIBUTE_METADATA_MAP.put(ATTR_CATEGORY_TYPE, new AttributeMetadata(ATTR_CATEGORY_TYPE, "category_type", String.class));
        ATTRIBUTE_METADATA_MAP.put(ATTR_CATEGORY_ENROLMENT_PERIOD, new AttributeMetadata(ATTR_CATEGORY_ENROLMENT_PERIOD, "enrolment_period_in_months", Integer.class));
        ATTRIBUTE_METADATA_MAP.put(ATTR_CATEGORY_MIN_PASS_LENGTH, new AttributeMetadata(ATTR_CATEGORY_MIN_PASS_LENGTH, "min_password_length", Integer.class, AttributeMetadata.Flags.NOT_CREATABLE, AttributeMetadata.Flags.NOT_UPDATEABLE));
        ATTRIBUTE_METADATA_MAP.put(ATTR_CATEGORY_STRONG_PASS, new AttributeMetadata(ATTR_CATEGORY_STRONG_PASS, "require_strong_password", Boolean.class, AttributeMetadata.Flags.NOT_CREATABLE, AttributeMetadata.Flags.NOT_UPDATEABLE));
        ATTRIBUTE_METADATA_MAP.put(ATTR_CATEGORY_UPPER_AGE_LIMIT, new AttributeMetadata(ATTR_CATEGORY_UPPER_AGE_LIMIT, "upper_age_limit", Integer.class, AttributeMetadata.Flags.NOT_CREATABLE, AttributeMetadata.Flags.NOT_UPDATEABLE));
        ATTRIBUTE_METADATA_MAP.put(ATTR_CATEGORY_LOWER_AGE_LIMIT, new AttributeMetadata(ATTR_CATEGORY_LOWER_AGE_LIMIT, "lower_age_limit", Integer.class, AttributeMetadata.Flags.NOT_CREATABLE, AttributeMetadata.Flags.NOT_UPDATEABLE));
    }

    /**
     * Construye un objeto JSON para una Categoría a partir de atributos de ConnId.
     */
    public JSONObject buildCategoryJson(Set<Attribute> attributes, boolean isCreate) {
        JSONObject jo = new JSONObject();
        Set<String> processedKohaAttrs = new HashSet<>();
        final String nameAttribute = "name";

        for (Attribute attr : attributes) {
            String connIdAttrName = attr.getName();
            if (Uid.NAME.equals(connIdAttrName)) continue;

            AttributeMetadata meta = Name.NAME.equals(connIdAttrName) ?
                    ATTRIBUTE_METADATA_MAP.get(nameAttribute) : ATTRIBUTE_METADATA_MAP.get(connIdAttrName);

            if (meta == null) {
                LOG.warn("BUILD_JSON: No hay metadatos para el atributo de Categoría '{0}'. Omitiendo.", connIdAttrName);
                continue;
            }

            if (isCreate && meta.isNotCreatable()) continue;
            if (!isCreate && meta.isNotUpdateable()) continue;

            List<Object> values = attr.getValue();
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
     * --- LÓGICA CORREGIDA ---
     * Convierte un objeto JSON de una Categoría en un ConnectorObject.
     */
    public ConnectorObject convertJsonToCategoryObject(JSONObject kohaJson) {
        if (kohaJson == null) return null;

        ConnectorObjectBuilder builder = new ConnectorObjectBuilder().setObjectClass(ObjectClass.GROUP);

        // 1. Lectura segura del UID y del NAME.
        // El UID de la categoría ('patron_category_id') puede ser un número.
        String uidVal = String.valueOf(kohaJson.get(KOHA_CATEGORY_ID_NATIVE_NAME));
        String nameVal = kohaJson.optString(ATTRIBUTE_METADATA_MAP.get("name").getKohaNativeName(), null);

        if (StringUtil.isBlank(uidVal)) {
            LOG.error("CONVERT_JSON: Se encontró una Categoría sin UID. Se omitirá. JSON: {0}", kohaJson.toString());
            return null;
        }

        builder.setUid(new Uid(uidVal));
        builder.setName(new Name(nameVal != null ? nameVal : uidVal));

        // 2. Mapear el resto de los atributos, omitiendo 'name' que ya se usó para __NAME__.
        for (AttributeMetadata meta : ATTRIBUTE_METADATA_MAP.values()) {
            if ("name".equals(meta.getConnIdName())) {
                continue; // Omitir, ya mapeado a __NAME__
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
