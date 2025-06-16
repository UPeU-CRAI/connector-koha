package com.identicum.connectors.mappers;

import com.identicum.connectors.model.AttributeMetadata;
import org.identityconnectors.framework.common.exceptions.InvalidAttributeValueException;
import org.json.JSONObject;
import org.junit.jupiter.api.Test; // JUnit 5
import static org.junit.jupiter.api.Assertions.*; // JUnit 5 Assertions

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public class BaseMapperTest {

    // Instancia anónima para probar los métodos protected de BaseMapper
    private final BaseMapper mapper = new BaseMapper() {};

    // === Pruebas para convertConnIdValueToKohaJsonValue ===

    @Test
    void testConnIdStringConversion() {
        AttributeMetadata meta = new AttributeMetadata("testString", "kohaString", String.class);
        assertEquals("hello", mapper.convertConnIdValueToKohaJsonValue("hello", meta));
    }

    @Test
    void testConnIdBooleanConversion() {
        AttributeMetadata meta = new AttributeMetadata("testBool", "kohaBool", Boolean.class);
        assertEquals(true, mapper.convertConnIdValueToKohaJsonValue(true, meta));
        assertEquals(false, mapper.convertConnIdValueToKohaJsonValue(false, meta));
    }

    @Test
    void testConnIdIntegerConversion() {
        AttributeMetadata meta = new AttributeMetadata("testInt", "kohaInt", Integer.class);
        assertEquals(123, mapper.convertConnIdValueToKohaJsonValue(123, meta));
    }

    @Test
    void testConnIdNullConversion() {
        AttributeMetadata meta = new AttributeMetadata("testNull", "kohaNull", String.class);
        assertEquals(JSONObject.NULL, mapper.convertConnIdValueToKohaJsonValue(null, meta));
    }

    @Test
    void testConnIdDateConversion_Valid() {
        AttributeMetadata metaDate = new AttributeMetadata("date_of_birth", "date_of_birth", String.class);
        assertEquals("2023-01-15", mapper.convertConnIdValueToKohaJsonValue("2023-01-15", metaDate));
    }

    @Test
    void testConnIdDateConversion_Invalid() {
        AttributeMetadata metaDate = new AttributeMetadata("date_of_birth", "date_of_birth", String.class);
        Exception exception = assertThrows(InvalidAttributeValueException.class, () -> {
            mapper.convertConnIdValueToKohaJsonValue("15/01/2023", metaDate);
        });
        assertTrue(exception.getMessage().contains("Formato de fecha inválido '15/01/2023'"));
    }

    // === Pruebas para convertKohaValueToConnIdValue ===

    @Test
    void testKohaStringConversion() {
        AttributeMetadata meta = new AttributeMetadata("testString", "kohaString", String.class);
        assertEquals("kohaValue", mapper.convertKohaValueToConnIdValue("kohaValue", meta));
    }

    @Test
    void testKohaBooleanConversion() {
        AttributeMetadata meta = new AttributeMetadata("testBool", "kohaBool", Boolean.class);
        assertEquals(true, mapper.convertKohaValueToConnIdValue(true, meta));
        assertEquals(false, mapper.convertKohaValueToConnIdValue(false, meta));
        assertEquals(true, mapper.convertKohaValueToConnIdValue("1", meta));
        assertEquals(false, mapper.convertKohaValueToConnIdValue("0", meta));
        assertEquals(true, mapper.convertKohaValueToConnIdValue("True", meta));
        assertEquals(false, mapper.convertKohaValueToConnIdValue("False", meta));
    }

    @Test
    void testKohaIntegerConversion() {
        AttributeMetadata meta = new AttributeMetadata("testInt", "kohaInt", Integer.class);
        assertEquals(456, mapper.convertKohaValueToConnIdValue(456, meta));
        assertEquals(789, mapper.convertKohaValueToConnIdValue("789", meta));
    }

    @Test
    void testKohaNullConversion() {
        AttributeMetadata meta = new AttributeMetadata("testNull", "kohaNull", String.class);
        assertNull(mapper.convertKohaValueToConnIdValue(null, meta));
        assertNull(mapper.convertKohaValueToConnIdValue(JSONObject.NULL, meta));
    }

    @Test
    void testKohaDateConversion_Valid() {
        AttributeMetadata metaDate = new AttributeMetadata("date_of_birth", "date_of_birth", String.class);
        assertEquals("2023-03-20", mapper.convertKohaValueToConnIdValue("2023-03-20", metaDate));
    }

    @Test
    void testKohaDateConversion_InvalidFormat() {
        AttributeMetadata metaDate = new AttributeMetadata("date_of_birth", "date_of_birth", String.class);
        // BaseMapper.convertKohaValueToConnIdValue logs a warning and returns null for parse errors
        assertNull(mapper.convertKohaValueToConnIdValue("20-03-2023", metaDate));
    }

    @Test
    void testKohaDateTimeConversion_Valid() {
        AttributeMetadata metaDateTime = new AttributeMetadata("updated_on", "updated_on", String.class);
        String kohaDateTime = "2023-03-20T10:30:00+02:00";
        // Should parse and reformat to ISO_OFFSET_DATE_TIME string (which it already is)
        assertEquals(kohaDateTime, mapper.convertKohaValueToConnIdValue(kohaDateTime, metaDateTime));
    }
}
