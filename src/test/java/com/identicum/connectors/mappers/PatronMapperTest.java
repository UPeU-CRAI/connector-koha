package com.identicum.connectors.mappers;

import com.identicum.connectors.model.AttributeMetadata;
import org.identityconnectors.framework.common.objects.*;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class PatronMapperTest {

    private PatronMapper patronMapper;
    private static final String TEST_UID = "123";
    private static final String TEST_USERID = "testuser";
    private static final String TEST_SURNAME = "Doe";
    private static final String TEST_FIRSTNAME = "John";
    private static final String TEST_CARDNUMBER = "C123456";
    private static final String TEST_LIBRARY_ID = "MAIN";
    private static final String TEST_CATEGORY_ID = "ADULT";
    private static final String TEST_EMAIL = "john.doe@example.com";


    @BeforeEach
    void setUp() {
        patronMapper = new PatronMapper();
        // Asegurar que los metadatos estén cargados si PatronMapper depende de inicialización estática
        // y las pruebas se ejecutan de forma que el static block no se haya ejecutado.
        // En este caso, PatronMapper.ATTRIBUTE_METADATA_MAP es estático y se inicializa una vez.
    }

    private Set<Attribute> buildSampleAttributesForCreate() {
        Set<Attribute> attributes = new HashSet<>();
        attributes.add(AttributeBuilder.build("userid", TEST_USERID)); // Name attribute via 'userid'
        attributes.add(AttributeBuilder.build("surname", TEST_SURNAME));
        attributes.add(AttributeBuilder.build("firstname", TEST_FIRSTNAME));
        attributes.add(AttributeBuilder.build("cardnumber", TEST_CARDNUMBER));
        attributes.add(AttributeBuilder.build("library_id", TEST_LIBRARY_ID));
        attributes.add(AttributeBuilder.build("category_id", TEST_CATEGORY_ID));
        attributes.add(AttributeBuilder.build(OperationalAttributes.ENABLE_NAME, true)); // Ejemplo de atributo operacional
        return attributes;
    }

    private Set<Attribute> buildSampleAttributesForUpdate() {
        Set<Attribute> attributes = new HashSet<>();
        attributes.add(AttributeBuilder.build("email", TEST_EMAIL));
        attributes.add(AttributeBuilder.build("phone", "123-456-7890"));
        return attributes;
    }

    @Test
    void testBuildPatronJson_Create() {
        Set<Attribute> attributes = buildSampleAttributesForCreate();
        JSONObject json = patronMapper.buildPatronJson(attributes, true);

        assertNotNull(json);
        assertEquals(TEST_USERID, json.getString("userid"));
        assertEquals(TEST_SURNAME, json.getString("surname"));
        assertEquals(TEST_FIRSTNAME, json.getString("firstname"));
        assertEquals(TEST_CARDNUMBER, json.getString("cardnumber"));
        assertEquals(TEST_LIBRARY_ID, json.getString("library_id"));
        assertEquals(TEST_CATEGORY_ID, json.getString("category_id"));

        // Atributos no creables no deben estar
        assertFalse(json.has("date_enrolled"));
        assertFalse(json.has(PatronMapper.KOHA_PATRON_ID_NATIVE_NAME)); // UID no se envía en create
    }

    @Test
    void testBuildPatronJson_Update() {
        Set<Attribute> attributes = buildSampleAttributesForUpdate();
        JSONObject json = patronMapper.buildPatronJson(attributes, false);

        assertNotNull(json);
        assertEquals(TEST_EMAIL, json.getString("email"));
        assertEquals("123-456-7890", json.getString("phone"));

        // Atributos no actualizables no deben estar, y tampoco los no enviados
        assertFalse(json.has("userid"));
        assertFalse(json.has("date_enrolled"));
    }

    @Test
    void testBuildPatronJson_ClearAttributeOnUpdate() {
        Set<Attribute> attributes = new HashSet<>();
        // Enviar null para borrar el atributo 'firstname'
        attributes.add(AttributeBuilder.build("firstname", (Object) null));
        JSONObject json = patronMapper.buildPatronJson(attributes, false);

        assertNotNull(json);
        assertTrue(json.has("firstname"));
        assertEquals(JSONObject.NULL, json.get("firstname"));
    }

    @Test
    void testConvertJsonToPatronObject_Basic() {
        JSONObject kohaJson = new JSONObject();
        kohaJson.put(PatronMapper.KOHA_PATRON_ID_NATIVE_NAME, TEST_UID);
        kohaJson.put("userid", TEST_USERID);
        kohaJson.put("surname", TEST_SURNAME);
        kohaJson.put("firstname", TEST_FIRSTNAME);
        kohaJson.put("cardnumber", TEST_CARDNUMBER);
        kohaJson.put("library_id", TEST_LIBRARY_ID);
        kohaJson.put("category_id", TEST_CATEGORY_ID);
        kohaJson.put("email", TEST_EMAIL);
        kohaJson.put("date_enrolled", "2023-01-01"); // Atributo no creable/actualizable pero leíble

        ConnectorObject co = patronMapper.convertJsonToPatronObject(kohaJson);

        assertNotNull(co);
        assertEquals(TEST_UID, co.getUid().getUidValue());
        assertEquals(TEST_USERID, co.getName().getNameValue()); // __NAME__

        assertEquals(TEST_SURNAME, AttributeUtil.getStringValue(co.getAttributeByName("surname")));
        assertEquals(TEST_FIRSTNAME, AttributeUtil.getStringValue(co.getAttributeByName("firstname")));
        assertEquals(TEST_CARDNUMBER, AttributeUtil.getStringValue(co.getAttributeByName("cardnumber")));
        assertEquals(TEST_LIBRARY_ID, AttributeUtil.getStringValue(co.getAttributeByName("library_id")));
        assertEquals(TEST_CATEGORY_ID, AttributeUtil.getStringValue(co.getAttributeByName("category_id")));
        assertEquals(TEST_EMAIL, AttributeUtil.getStringValue(co.getAttributeByName("email")));
        assertEquals("2023-01-01", AttributeUtil.getStringValue(co.getAttributeByName("date_enrolled")));
    }

    @Test
    void testConvertJsonToPatronObject_NullAndMissingAttributes() {
        JSONObject kohaJson = new JSONObject();
        kohaJson.put(PatronMapper.KOHA_PATRON_ID_NATIVE_NAME, TEST_UID);
        kohaJson.put("userid", TEST_USERID);
        kohaJson.put("surname", TEST_SURNAME);
        // firstname es omitido
        kohaJson.put("email", JSONObject.NULL); // email es explícitamente null

        ConnectorObject co = patronMapper.convertJsonToPatronObject(kohaJson);

        assertNotNull(co);
        assertEquals(TEST_UID, co.getUid().getUidValue());
        assertEquals(TEST_USERID, co.getName().getNameValue());
        assertEquals(TEST_SURNAME, AttributeUtil.getStringValue(co.getAttributeByName("surname")));

        assertNull(co.getAttributeByName("firstname")); // No debería estar presente
        assertNull(co.getAttributeByName("email"));     // Debería ser null o no estar presente
    }

    @Test
    void testConvertJsonToPatronObject_NoUid() {
        JSONObject kohaJson = new JSONObject();
        kohaJson.put("userid", TEST_USERID);
        kohaJson.put("surname", TEST_SURNAME);
        // Falta KOHA_PATRON_ID_NATIVE_NAME

        ConnectorObject co = patronMapper.convertJsonToPatronObject(kohaJson);
        assertNull(co); // Debería retornar null según la lógica del mapper
    }
}
