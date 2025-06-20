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

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.identityconnectors.common.logging.Log;

/**
 * Mapper especializado para los objetos de tipo Categoría de Patrón (Group).
 * Contiene las definiciones de atributos de categorías y la lógica para su transformación.
 */
public class CategoryMapper extends BaseMapper {
    private static final Log LOG = Log.getLog(CategoryMapper.class);

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
        LOG.ok("Construyendo JSON de Categoría para {0}. Atributos ConnId: {1}", (isCreate ? "CREATE" : "UPDATE"), attributes);
        JSONObject jo = new JSONObject();
        Set<String> processedKohaAttrs = new HashSet<>();
        final String nameAttribute = "name"; // ConnId Name attribute for Categories

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
                LOG.warn("No hay metadatos para el atributo de Categoría '{0}'. Omitiendo.", connIdAttrName);
                continue;
            }

            if (isCreate && meta.isNotCreatable()) {
                LOG.ok("Trace Mapper: Omitiendo atributo {0} debido a que es no creable", connIdAttrName);
                continue;
            }
            if (!isCreate && meta.isNotUpdateable()) {
                LOG.ok("Trace Mapper: Omitiendo atributo {0} debido a que es no actualizable", connIdAttrName);
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

            if (processedKohaAttrs.contains(meta.getKohaNativeName())) {
                LOG.ok("Trace Mapper: Atributo Koha nativo '{0}' ya procesado (posiblemente por Name y '{1}' mapeando al mismo). Omitiendo para ConnId '{1}'.", meta.getKohaNativeName(), connIdAttrName);
                continue;
            }

            Object kohaValue = meta.isMultivalued() ?
                    new JSONArray(values.stream().map(v -> convertConnIdValueToKohaJsonValue(v, meta)).filter(Objects::nonNull).toArray())
                    : convertConnIdValueToKohaJsonValue(values.get(0), meta);

            LOG.ok("Trace Mapper: Mapeando ConnId '{0}' a Koha '{1}' con valor: {2}", connIdAttrName, meta.getKohaNativeName(), kohaValue);
            jo.put(meta.getKohaNativeName(), kohaValue);
            processedKohaAttrs.add(meta.getKohaNativeName());
        }
        LOG.ok("JSON de Categoría construido: {0}", jo.toString(2));
        return jo;
    }

    /**
     * --- LÓGICA CORREGIDA ---
     * Convierte un objeto JSON de una Categoría en un ConnectorObject.
     */
    public ConnectorObject convertJsonToCategoryObject(JSONObject kohaJson) {
        LOG.ok("Convirtiendo JSON de Koha a ConnectorObject (Categoría): {0}", kohaJson.toString(2));
        if (kohaJson == null) {
            LOG.warn("El JSON de Koha proporcionado es nulo. Retornando nulo.");
            return null;
        }

        ConnectorObjectBuilder builder = new ConnectorObjectBuilder().setObjectClass(ObjectClass.GROUP);

        // UID es mandatorio. Koha usa 'patron_category_id'.
        Object rawUid = kohaJson.opt(KOHA_CATEGORY_ID_NATIVE_NAME);
        String uidVal = (rawUid != null) ? String.valueOf(rawUid) : null;

        if (StringUtil.isBlank(uidVal)) {
            LOG.error("Categoría JSON no tiene UID ({0}). JSON: {1}", KOHA_CATEGORY_ID_NATIVE_NAME, kohaJson.toString(2));
            return null; // No se puede construir un ConnectorObject sin UID
        }
        builder.setUid(new Uid(uidVal));

        // Name attribute - ConnId 'Name' (que mapea a 'description' de Koha para categorías)
        AttributeMetadata nameAttributeMeta = ATTRIBUTE_METADATA_MAP.get("name"); // "name" es el ConnId Name para Categorías
        String nameVal = null;
        if (nameAttributeMeta != null && kohaJson.has(nameAttributeMeta.getKohaNativeName())) {
            nameVal = kohaJson.optString(nameAttributeMeta.getKohaNativeName(), null);
            LOG.ok("Trace Mapper: Mapeando Koha '{0}' (description) a ConnId Name con valor: {1}", nameAttributeMeta.getKohaNativeName(), nameVal);
        } else {
            LOG.ok("Trace Mapper: Atributo Koha para Name (mapeado de 'description') no encontrado o nulo en JSON. Usando UID como Name fallback.");
        }
        builder.setName(new Name(nameVal != null ? nameVal : uidVal)); // Fallback a UID si 'description' no está o es nulo.


        // Resto de los atributos
        for (AttributeMetadata meta : ATTRIBUTE_METADATA_MAP.values()) {
            LOG.ok("Trace Mapper: Considerando atributo Koha '{0}' (ConnId: '{1}')", meta.getKohaNativeName(), meta.getConnIdName());

            // Omitir el que ya se usó para Name.NAME si su ConnIdName es "name" (el ConnId Name para Categorías)
            if ("name".equals(meta.getConnIdName())) {
                LOG.ok("Trace Mapper: Omitiendo el procesamiento explícito de '{0}' como atributo regular, ya manejado como Name.NAME.", meta.getConnIdName());
                continue;
            }

            // No omitir el atributo 'name' aquí si queremos que esté disponible tanto en Name.NAME como en el set de atributos.
            // La lógica de Name.NAME ya lo ha tomado de kohaJson.getString("description").
            // Si 'meta.getConnIdName()' es "name", se procesará y añadirá.

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
        LOG.ok("ConnectorObject (Categoría) construido: {0}", resultObject.toString());
        return resultObject;
    }
}
