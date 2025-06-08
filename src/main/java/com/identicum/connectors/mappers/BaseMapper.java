package com.identicum.connectors.mappers;

import com.identicum.connectors.model.AttributeMetadata;
import org.identityconnectors.common.logging.Log;
import org.identityconnectors.framework.common.exceptions.InvalidAttributeValueException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Clase base abstracta para los mappers.
 * Contiene la lógica genérica de conversión de tipos de datos entre ConnId y JSON.
 */
public abstract class BaseMapper {

    protected static final Log LOG = Log.getLog(BaseMapper.class);

    // Formateadores de fecha/hora comunes
    protected static final DateTimeFormatter KOHA_DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;
    protected static final DateTimeFormatter KOHA_DATETIME_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /**
     * Convierte un valor de atributo de ConnId al formato JSON apropiado para Koha.
     * @param connIdValue El valor proveniente de ConnId.
     * @param meta Los metadatos del atributo que se está convirtiendo.
     * @return El valor en un formato compatible con JSON (String, Integer, Boolean, etc.).
     */
    protected Object convertConnIdValueToKohaJsonValue(Object connIdValue, AttributeMetadata meta) {
        if (connIdValue == null) {
            return JSONObject.NULL;
        }
        Class<?> connIdType = meta.getType();
        String connIdAttrName = meta.getConnIdName();

        if (connIdType.equals(String.class)) {
            // Manejo especial para fechas que se envían como String
            if (connIdAttrName.equals("date_of_birth") || connIdAttrName.equals("expiry_date")) {
                try {
                    // Validamos el formato pero lo devolvemos como string
                    return LocalDate.parse(connIdValue.toString(), KOHA_DATE_FORMATTER).toString();
                } catch (DateTimeParseException e) {
                    throw new InvalidAttributeValueException("Fecha inválida " + connIdValue + " para " + connIdAttrName, e);
                }
            }
            return connIdValue.toString();
        } else if (connIdType.equals(Boolean.class) || connIdType.equals(Integer.class) || connIdType.equals(Long.class)) {
            return connIdValue;
        }
        return connIdValue.toString();
    }

    /**
     * Convierte un valor nativo de la API de Koha al tipo de dato esperado por ConnId.
     * @param kohaValue El valor proveniente del JSON de Koha.
     * @param meta Los metadatos del atributo que se está convirtiendo.
     * @return El valor en el tipo de dato correcto para ConnId.
     */
    protected Object convertKohaValueToConnIdValue(Object kohaValue, AttributeMetadata meta) {
        if (kohaValue == null || JSONObject.NULL.equals(kohaValue)) {
            return null;
        }
        Class<?> connIdType = meta.getType();
        String connIdAttrName = meta.getConnIdName();

        try {
            if (connIdType.equals(String.class)) {
                String kohaString = kohaValue.toString();
                // Manejo especial para fechas/horas que se reciben como String
                if (connIdAttrName.equals("date_of_birth") || connIdAttrName.equals("expiry_date") ||
                        connIdAttrName.equals("date_enrolled") || connIdAttrName.equals("date_renewed")) {
                    return LocalDate.parse(kohaString, KOHA_DATE_FORMATTER).toString();
                } else if (connIdAttrName.equals("updated_on") || connIdAttrName.equals("last_seen")) {
                    return ZonedDateTime.parse(kohaString, KOHA_DATETIME_FORMATTER).toString();
                }
                return kohaString;
            } else if (connIdType.equals(Boolean.class)) {
                if (kohaValue instanceof Boolean) return kohaValue;
                // Koha a menudo devuelve '0' o '1' para los booleanos
                return "true".equalsIgnoreCase(kohaValue.toString()) || "1".equals(kohaValue.toString());
            } else if (connIdType.equals(Integer.class)) {
                if (kohaValue instanceof Integer) return kohaValue;
                return Integer.parseInt(kohaValue.toString());
            } else if (connIdType.equals(Long.class)) {
                if (kohaValue instanceof Long) return kohaValue;
                return Long.parseLong(kohaValue.toString());
            }
        } catch (NumberFormatException | DateTimeParseException e) {
            LOG.warn("CONVERT_KOHA_VALUE: Error de parseo para ''{0}'' (attr: {1}) a ''{2}''. Error: {3}",
                    kohaValue, connIdAttrName, connIdType.getSimpleName(), e.getMessage());
            return null; // Omitir atributo si no se puede parsear
        }
        return kohaValue.toString();
    }
}