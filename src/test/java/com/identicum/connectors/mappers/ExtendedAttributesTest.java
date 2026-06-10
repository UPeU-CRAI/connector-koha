package com.identicum.connectors.mappers;

import org.identityconnectors.framework.common.objects.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ExtendedAttributesTest {

    private final PatronMapper mapper = new PatronMapper();

    @Test
    void testReadExtendedAttributesFromKoha() {
        JSONObject kohaJson = new JSONObject();
        kohaJson.put("patron_id", 42);
        kohaJson.put("userid", "testuser");
        JSONArray extAttrs = new JSONArray();
        extAttrs.put(new JSONObject().put("type", "DNI").put("value", "12345678"));
        extAttrs.put(new JSONObject().put("type", "ORCID").put("value", "0000-0001-2345-6789"));
        kohaJson.put("extended_attributes", extAttrs);

        ConnectorObject co = mapper.convertJsonToPatronObject(kohaJson);
        assertNotNull(co);
        Attribute attr = co.getAttributeByName("extended_attributes");
        assertNotNull(attr, "extended_attributes should be present");
        List<Object> values = attr.getValue();
        assertEquals(2, values.size());
        // Each value should be a JSON string
        String first = values.get(0).toString();
        assertTrue(first.contains("\"type\"") && first.contains("\"DNI\"") && first.contains("\"value\"") && first.contains("\"12345678\""));
    }

    @Test
    void testReadEmptyExtendedAttributes() {
        // v1.3.11: cuando el embed esta presente pero la lista es vacia, el atributo
        // DEBE emitirse (vacio) para que MidPoint lo trate como "completo" y pueda
        // computar el delta aditivo (backfill). Antes se omitia -> MidPoint lo marcaba
        // incompleto y suprimia el add (causa raiz del fallo de backfill de STUDYCYCLE).
        JSONObject kohaJson = new JSONObject();
        kohaJson.put("patron_id", 42);
        kohaJson.put("userid", "testuser");
        kohaJson.put("extended_attributes", new JSONArray());

        ConnectorObject co = mapper.convertJsonToPatronObject(kohaJson);
        assertNotNull(co);
        Attribute attr = co.getAttributeByName("extended_attributes");
        assertNotNull(attr, "Empty extended_attributes (embed present) must be emitted to mark the attribute complete");
        assertTrue(attr.getValue() == null || attr.getValue().isEmpty(),
                "Empty embed must produce an attribute with no values");
    }

    @Test
    void testReadAbsentExtendedAttributesKey() {
        // Si el JSON no trae la clave (GET sin embed, fuera del flujo de reconcile),
        // el atributo se omite — no podemos afirmar nada sobre su estado.
        JSONObject kohaJson = new JSONObject();
        kohaJson.put("patron_id", 42);
        kohaJson.put("userid", "testuser");

        ConnectorObject co = mapper.convertJsonToPatronObject(kohaJson);
        assertNotNull(co);
        Attribute attr = co.getAttributeByName("extended_attributes");
        assertNull(attr, "Absent embed key must not produce an extended_attributes attribute");
    }

    @Test
    void testWriteExtendedAttributesToKoha() {
        Set<Attribute> attrs = new HashSet<>();
        attrs.add(AttributeBuilder.build("userid", "testuser"));
        attrs.add(AttributeBuilder.build("surname", "Test"));
        attrs.add(AttributeBuilder.build("library_id", "BUL"));
        attrs.add(AttributeBuilder.build("category_id", "ESTUDI"));
        attrs.add(AttributeBuilder.build("cardnumber", "12345"));
        List<String> extValues = Arrays.asList(
            "{\"type\":\"DNI\",\"value\":\"12345678\"}",
            "{\"type\":\"ORCID\",\"value\":\"0000-0001-2345-6789\"}"
        );
        attrs.add(AttributeBuilder.build("extended_attributes", extValues));

        JSONObject json = mapper.buildPatronJson(attrs, true);
        assertTrue(json.has("extended_attributes"));
        JSONArray extArr = json.getJSONArray("extended_attributes");
        assertEquals(2, extArr.length());
        assertEquals("DNI", extArr.getJSONObject(0).getString("type"));
    }

    @Test
    void testWriteInvalidExtendedAttribute() {
        Set<Attribute> attrs = new HashSet<>();
        attrs.add(AttributeBuilder.build("userid", "testuser"));
        attrs.add(AttributeBuilder.build("surname", "Test"));
        attrs.add(AttributeBuilder.build("library_id", "BUL"));
        attrs.add(AttributeBuilder.build("category_id", "ESTUDI"));
        attrs.add(AttributeBuilder.build("cardnumber", "12345"));
        attrs.add(AttributeBuilder.build("extended_attributes", Arrays.asList("not-json", "{\"type\":\"DNI\",\"value\":\"123\"}")));

        JSONObject json = mapper.buildPatronJson(attrs, true);
        JSONArray extArr = json.getJSONArray("extended_attributes");
        assertEquals(1, extArr.length()); // Invalid one skipped
        assertEquals("DNI", extArr.getJSONObject(0).getString("type"));
    }

    @Test
    void testRoundTrip() {
        // Write to Koha format
        Set<Attribute> attrs = new HashSet<>();
        attrs.add(AttributeBuilder.build("userid", "roundtrip"));
        attrs.add(AttributeBuilder.build("surname", "Test"));
        attrs.add(AttributeBuilder.build("library_id", "BUL"));
        attrs.add(AttributeBuilder.build("category_id", "ESTUDI"));
        attrs.add(AttributeBuilder.build("cardnumber", "99999"));
        attrs.add(AttributeBuilder.build("extended_attributes", Collections.singletonList("{\"type\":\"PASSPORT\",\"value\":\"AB123456\"}")));

        JSONObject kohaJson = mapper.buildPatronJson(attrs, true);
        // Simulate Koha response with patron_id
        kohaJson.put("patron_id", 999);

        // Read back from Koha format
        ConnectorObject co = mapper.convertJsonToPatronObject(kohaJson);
        Attribute extAttr = co.getAttributeByName("extended_attributes");
        assertNotNull(extAttr);
        String val = extAttr.getValue().get(0).toString();
        assertTrue(val.contains("PASSPORT") && val.contains("AB123456"));
    }
}
