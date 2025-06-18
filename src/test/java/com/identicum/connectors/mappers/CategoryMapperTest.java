package com.identicum.connectors.mappers;

import org.identityconnectors.framework.common.objects.*;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class CategoryMapperTest {

    private CategoryMapper categoryMapper;
    private static final String TEST_UID = "C1";
    private static final String TEST_CATEGORY_NAME = "Adults"; // ConnId Name
    private static final String TEST_CATEGORY_KOHA_NAME = "Adult Patrons"; // Koha's 'description' field
    private static final String TEST_CATEGORY_TYPE = "A";
    private static final Integer TEST_ENROLMENT_PERIOD = 12; // months

    @BeforeEach
    void setUp() {
        categoryMapper = new CategoryMapper();
    }

    private Set<Attribute> buildSampleAttributesForCreate() {
        Set<Attribute> attributes = new HashSet<>();
        // El atributo Name de ConnId se mapea al 'description' de Koha para categorías
        attributes.add(AttributeBuilder.build(Name.NAME, TEST_CATEGORY_KOHA_NAME));
        attributes.add(AttributeBuilder.build("category_type", TEST_CATEGORY_TYPE));
        attributes.add(AttributeBuilder.build("enrolment_period", TEST_ENROLMENT_PERIOD));
        return attributes;
    }

    private Set<Attribute> buildSampleAttributesForUpdate() {
        Set<Attribute> attributes = new HashSet<>();
        attributes.add(AttributeBuilder.build("enrolment_period", 24));
        return attributes;
    }

    @Test
    void testBuildCategoryJson_Create() {
        Set<Attribute> attributes = buildSampleAttributesForCreate();
        JSONObject json = categoryMapper.buildCategoryJson(attributes, true);

        assertNotNull(json);
        // 'name' (ConnId) se mapea a 'description' (Koha)
        assertEquals(TEST_CATEGORY_KOHA_NAME, json.getString("description"));
        assertEquals(TEST_CATEGORY_TYPE, json.getString("category_type"));
        assertEquals(TEST_ENROLMENT_PERIOD, json.getInt("enrolment_period_in_months"));

        // Atributos no creables no deben estar
        assertFalse(json.has("min_password_length"));
        assertFalse(json.has(CategoryMapper.KOHA_CATEGORY_ID_NATIVE_NAME));
    }

    @Test
    void testBuildCategoryJson_Update() {
        Set<Attribute> attributes = buildSampleAttributesForUpdate();
        JSONObject json = categoryMapper.buildCategoryJson(attributes, false);

        assertNotNull(json);
        assertEquals(24, json.getInt("enrolment_period_in_months"));

        assertFalse(json.has("description")); // No se envió 'name' para actualizar
        assertFalse(json.has("min_password_length"));
    }

    @Test
    void testConvertJsonToCategoryObject_Basic() {
        JSONObject kohaJson = new JSONObject();
        kohaJson.put(CategoryMapper.KOHA_CATEGORY_ID_NATIVE_NAME, TEST_UID);
        // Koha usa 'description' para el nombre de la categoría
        kohaJson.put("description", TEST_CATEGORY_KOHA_NAME);
        kohaJson.put("category_type", TEST_CATEGORY_TYPE);
        kohaJson.put("enrolment_period_in_months", TEST_ENROLMENT_PERIOD);
        // Atributo no creable/actualizable pero leíble
        kohaJson.put("min_password_length", 8);

        ConnectorObject co = categoryMapper.convertJsonToCategoryObject(kohaJson);

        assertNotNull(co);
        assertEquals(TEST_UID, co.getUid().getUidValue());
        // __NAME__ debe ser el valor de 'description' de Koha
        assertEquals(TEST_CATEGORY_KOHA_NAME, co.getName().getNameValue());

        assertEquals(TEST_CATEGORY_TYPE, AttributeUtil.getStringValue(co.getAttributeByName("category_type")));
        assertEquals(TEST_ENROLMENT_PERIOD, AttributeUtil.getIntegerValue(co.getAttributeByName("enrolment_period")));
        assertEquals(Integer.valueOf(8), AttributeUtil.getIntegerValue(co.getAttributeByName("min_password_length")));
    }

    @Test
    void testConvertJsonToCategoryObject_NameMapping() {
        // Prueba específica para asegurar que el Name.NAME de ConnId (que es __NAME__)
        // se mapea correctamente desde/hacia 'description' de Koha.
        // Y que el atributo 'name' de AttributeMetadata (que es el ConnId 'name') también lo hace.

        // 1. ConnId Attrs -> JSON
        Set<Attribute> attrsToKoha = new HashSet<>();
        attrsToKoha.add(AttributeBuilder.build(Name.NAME, "Special Category Name"));
        JSONObject jsonToKoha = categoryMapper.buildCategoryJson(attrsToKoha, true);
        assertEquals("Special Category Name", jsonToKoha.getString("description"));

        // 2. JSON -> ConnId Object
        JSONObject jsonFromKoha = new JSONObject();
        jsonFromKoha.put(CategoryMapper.KOHA_CATEGORY_ID_NATIVE_NAME, "C2");
        jsonFromKoha.put("description", "Another Category Name");
        ConnectorObject coFromKoha = categoryMapper.convertJsonToCategoryObject(jsonFromKoha);
        assertEquals("Another Category Name", coFromKoha.getName().getNameValue());
        // También verificamos que el atributo 'name' (si se pidiera explícitamente) tendría el mismo valor
    }


    @Test
    void testConvertJsonToCategoryObject_NullAndMissingAttributes() {
        JSONObject kohaJson = new JSONObject();
        kohaJson.put(CategoryMapper.KOHA_CATEGORY_ID_NATIVE_NAME, TEST_UID);
        kohaJson.put("description", TEST_CATEGORY_KOHA_NAME);
        // category_type es omitido
        kohaJson.put("enrolment_period_in_months", JSONObject.NULL);

        ConnectorObject co = categoryMapper.convertJsonToCategoryObject(kohaJson);

        assertNotNull(co);
        assertEquals(TEST_UID, co.getUid().getUidValue());
        assertEquals(TEST_CATEGORY_KOHA_NAME, co.getName().getNameValue());

        assertNull(co.getAttributeByName("category_type"));
        assertNull(co.getAttributeByName("enrolment_period"));
    }

    @Test
    void testConvertJsonToCategoryObject_NoUid() {
        JSONObject kohaJson = new JSONObject();
        kohaJson.put("description", TEST_CATEGORY_KOHA_NAME);
        // Falta KOHA_CATEGORY_ID_NATIVE_NAME

        ConnectorObject co = categoryMapper.convertJsonToCategoryObject(kohaJson);
        assertNull(co);
    }

    @Test
    void testConvertJsonToCategoryObject_NameAttributeNotDuplicated() {
        categoryMapper = new CategoryMapper(); // Asegurar inicialización si no es global o en @BeforeEach
        JSONObject kohaJson = new JSONObject();
        final String testUid = "TEST_UID_CAT";
        final String testDescription = "Test Category Name";

        kohaJson.put(CategoryMapper.KOHA_CATEGORY_ID_NATIVE_NAME, testUid);
        kohaJson.put("description", testDescription); // 'description' de Koha es el 'Name' de ConnId para categorías
        kohaJson.put("category_type", "SOMETYPE"); // Otro atributo para que el objeto no esté vacío

        ConnectorObject co = categoryMapper.convertJsonToCategoryObject(kohaJson);

        assertNotNull(co, "El ConnectorObject no debería ser nulo.");
        assertEquals(testUid, co.getUid().getUidValue(), "El UID no coincide.");
        assertEquals(testDescription, co.getName().getNameValue(), "El Name.NAME no coincide.");

        // Verificar que el atributo "name" (el ConnId 'name') NO está presente en el conjunto de atributos,
        // ya que su valor ya está en Name.NAME y la lógica modificada debería omitirlo.
        assertNull(co.getAttributeByName("name"), "El atributo 'name' no debería estar duplicado en los atributos.");
        // Para ser más explícito, también podemos verificar que el atributo 'description' (el nativo de Koha) tampoco esté.
        assertNull(co.getAttributeByName("description"), "El atributo 'description' (nativo de Koha para nombre) no debería estar.");
        // Pero sí debería estar cualquier otro atributo que se haya añadido:
        assertNotNull(co.getAttributeByName("category_type"), "El atributo 'category_type' debería estar presente.");
        assertEquals("SOMETYPE", AttributeUtil.getStringValue(co.getAttributeByName("category_type")));
    }
}
