package com.identicum.connectors.mappers;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

class PatronMapperEnableTest {

    private final PatronMapper mapper = new PatronMapper();

    @Test
    void testComputeEnabled_ActivePatron() {
        JSONObject json = new JSONObject();
        json.put("patron_card_lost", false);
        json.put("expired", false);
        assertTrue(mapper.computeEnabled(json));
    }

    @Test
    void testComputeEnabled_CardLost() {
        JSONObject json = new JSONObject();
        json.put("patron_card_lost", true);
        json.put("expired", false);
        assertFalse(mapper.computeEnabled(json));
    }

    @Test
    void testComputeEnabled_Expired() {
        JSONObject json = new JSONObject();
        json.put("patron_card_lost", false);
        json.put("expired", true);
        assertFalse(mapper.computeEnabled(json));
    }

    @Test
    void testComputeEnabled_BothDisabled() {
        JSONObject json = new JSONObject();
        json.put("patron_card_lost", true);
        json.put("expired", true);
        assertFalse(mapper.computeEnabled(json));
    }

    @Test
    void testComputeEnabled_MissingFields() {
        JSONObject json = new JSONObject();
        assertTrue(mapper.computeEnabled(json));
    }

    @Test
    void testApplyEnableDisable() {
        JSONObject payload = new JSONObject();
        mapper.applyEnableAttribute(payload, false);
        assertTrue(payload.getBoolean("patron_card_lost"));
        assertEquals(LocalDate.now().minusDays(1).toString(), payload.getString("expiry_date"));
    }

    @Test
    void testApplyEnableActivate() {
        JSONObject payload = new JSONObject();
        payload.put("expiry_date", "2030-12-31");
        mapper.applyEnableAttribute(payload, true);
        assertFalse(payload.getBoolean("patron_card_lost"));
        assertEquals("2030-12-31", payload.getString("expiry_date")); // expiry_date NOT cleared
    }

    @Test
    void testApplyEnableNull() {
        JSONObject payload = new JSONObject();
        payload.put("patron_card_lost", false);
        mapper.applyEnableAttribute(payload, null);
        assertFalse(payload.getBoolean("patron_card_lost")); // No change
    }
}
