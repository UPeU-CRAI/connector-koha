# Tasks: Koha Connector v1.2.0

**Spec:** doc/specs/koha-connector-v1.2-upgrade/02-specification.md
**Created:** 2026-04-16 21:00
**Last Updated:** 2026-04-16 21:00
**Last Decompose:** 2026-04-16 21:00

## Summary

| Status | Count |
|--------|-------|
| ⏳ Pending | 16 |
| 🔄 In Progress | 0 |
| ✅ Completed | 0 |
| **Total** | **16** |

---

## Phase 1: Core API Compatibility

### Task 1.1: Version bump + PATCH migration
**Status:** ⏳ pending
**Priority:** high
**Depends On:** none

**Description:**
1. `pom.xml`: Change `<version>1.1.0</version>` to `<version>1.2.0</version>`
2. `PatronService.java`: Replace `HttpPut` with `HttpPatch` in `updatePatron()`, add import `org.apache.http.client.methods.HttpPatch`
3. `AbstractKohaService.java` line 249: Add `"PATCH"` to the 404 handler condition
4. `KohaConnector.java` `update()` method: Remove GET-before-update. Replace lines 150-165 with direct PATCH:
   ```java
   if (ObjectClass.ACCOUNT.is(oClass.getObjectClassValue())) {
       JSONObject changes = patronMapper.buildPatronJson(attrs, false);
       patronService.updatePatron(uid.getUidValue(), changes);
   }
   ```

**Acceptance Criteria:**
- [ ] pom.xml version is 1.2.0
- [ ] PatronService.updatePatron uses HttpPatch
- [ ] AbstractKohaService handles PATCH in 404 response
- [ ] KohaConnector.update() sends PATCH directly without pre-fetch GET
- [ ] Existing PatronServiceTest passes with updated method

**Files to Modify:**
- `pom.xml`
- `src/main/java/com/identicum/connectors/services/PatronService.java`
- `src/main/java/com/identicum/connectors/services/AbstractKohaService.java`
- `src/main/java/com/identicum/connectors/KohaConnector.java`

---

### Task 1.2: Categories read-only
**Status:** ⏳ pending
**Priority:** high
**Depends On:** none

**Description:**
1. `CategoryService.java`: Remove `createCategory()`, `updateCategory()`, `deleteCategory()` methods. Keep `getCategory()` and `searchCategories()`.
2. `KohaConnector.java`:
   - `create()`: Replace GROUP branch with `throw new UnsupportedOperationException("Patron categories are read-only in Koha API")`
   - `update()`: Same for GROUP
   - `delete()`: Same for GROUP
3. `CategoryMapper.java`: Add `categorycode` attribute mapped to `patron_category_id`. Add `enrolment_fee` (String), `overdue_notice_required` (Boolean), `default_privacy` (String).

**Acceptance Criteria:**
- [ ] CategoryService has no create/update/delete methods
- [ ] KohaConnector throws UnsupportedOperationException for GROUP write operations
- [ ] CategoryMapper includes categorycode and expanded attributes
- [ ] Existing CategoryServiceTest updated (remove write tests)

**Files to Modify:**
- `src/main/java/com/identicum/connectors/services/CategoryService.java`
- `src/main/java/com/identicum/connectors/KohaConnector.java`
- `src/main/java/com/identicum/connectors/mappers/CategoryMapper.java`

---

### Task 1.3: Enhanced DELETE 409 handling
**Status:** ⏳ pending
**Priority:** high
**Depends On:** none

**Description:**
In `AbstractKohaService.processResponseErrors()`, replace the generic 409 case with differentiated messages:
```java
case 409:
    String lowerBody409 = body.toLowerCase();
    String detail = "Conflict";
    if (lowerBody409.contains("has_checkouts")) {
        detail = "Patron has active checkouts and cannot be deleted";
    } else if (lowerBody409.contains("has_debt")) {
        detail = "Patron has outstanding debt and cannot be deleted";
    } else if (lowerBody409.contains("is_protected")) {
        detail = "Patron is protected and cannot be deleted";
    } else if (lowerBody409.contains("has_guarantees")) {
        detail = "Patron has guarantees and cannot be deleted";
    } else if (lowerBody409.contains("is_anonymous_patron")) {
        detail = "Anonymous patron cannot be deleted";
    }
    throw new AlreadyExistsException(resourceContext + " " + detail + ". Request: " + requestDesc + ", Body: " + body);
```

**Acceptance Criteria:**
- [ ] 409 with "has_checkouts" produces specific message
- [ ] 409 with "has_debt" produces specific message
- [ ] 409 with "is_protected" produces specific message
- [ ] 409 with generic content falls back to "Conflict"

**Files to Modify:**
- `src/main/java/com/identicum/connectors/services/AbstractKohaService.java`

---

## Phase 2: Lifecycle Support

### Task 2.1: __ENABLE__ support (dual mechanism)
**Status:** ⏳ pending
**Priority:** high
**Depends On:** Task 1.1

**Description:**
Implement `OperationalAttributes.ENABLE_NAME` with dual mechanism:
- `patron_card_lost=true` → immediate block (override)
- If `patron_card_lost` is false/null → `expiry_date` determines active state
- Read: `__ENABLE__ = !patron_card_lost && !expired`
- Write disable: `patron_card_lost=true` + `expiry_date=yesterday`
- Write enable: `patron_card_lost=false` (expiry_date NOT touched)

**In `PatronMapper.java`:**
Add methods:
```java
public void applyEnableAttribute(JSONObject payload, Boolean enabled) {
    if (enabled == null) return;
    if (!enabled) {
        payload.put("patron_card_lost", true);
        payload.put("expiry_date", LocalDate.now().minusDays(1).toString());
    } else {
        payload.put("patron_card_lost", false);
    }
}

public boolean computeEnabled(JSONObject kohaJson) {
    boolean cardLost = kohaJson.optBoolean("patron_card_lost", false);
    boolean expired = kohaJson.optBoolean("expired", false);
    return !cardLost && !expired;
}
```

In `convertJsonToPatronObject()`: Add `__ENABLE__` attribute via `builder.addAttribute(OperationalAttributes.ENABLE_NAME, computeEnabled(kohaJson))`.

**In `KohaConnector.java`:**
- `schema()`: Add `OperationalAttributeInfos.ENABLE` to ACCOUNT ObjectClassInfoBuilder
- `create()`: Extract `__ENABLE__` from attrs via `AttributeUtil.find(OperationalAttributes.ENABLE_NAME, attrs)`. After building patron JSON, call `patronMapper.applyEnableAttribute(payload, enabled)`.
- `update()`: Same extraction and application of __ENABLE__.

**Acceptance Criteria:**
- [ ] Schema includes __ENABLE__ for ACCOUNT
- [ ] Create with __ENABLE__=false sets patron_card_lost=true and expiry_date=yesterday
- [ ] Create with __ENABLE__=true sets patron_card_lost=false
- [ ] Update with __ENABLE__=false disables patron
- [ ] Update with __ENABLE__=true re-enables patron
- [ ] Read returns __ENABLE__=false when patron_card_lost=true
- [ ] Read returns __ENABLE__=false when expired=true
- [ ] Read returns __ENABLE__=true when both are false

**Files to Modify:**
- `src/main/java/com/identicum/connectors/mappers/PatronMapper.java`
- `src/main/java/com/identicum/connectors/KohaConnector.java`

---

### Task 2.2: date_enrolled CREATABLE
**Status:** ⏳ pending
**Priority:** medium
**Depends On:** none

**Description:**
In `PatronMapper.java`, change `date_enrolled` metadata from `NOT_CREATABLE, NOT_UPDATEABLE` to only `NOT_UPDATEABLE`:
```java
ATTRIBUTE_METADATA_MAP.put(ATTR_DATE_ENROLLED, new AttributeMetadata(ATTR_DATE_ENROLLED, "date_enrolled", String.class, AttributeMetadata.Flags.NOT_UPDATEABLE));
```

**Acceptance Criteria:**
- [ ] date_enrolled can be set during create operations
- [ ] date_enrolled is still NOT settable during update operations

**Files to Modify:**
- `src/main/java/com/identicum/connectors/mappers/PatronMapper.java`

---

### Task 2.3: Extended attributes serialization (JSON string)
**Status:** ⏳ pending
**Priority:** high
**Depends On:** none

**Description:**
Implement dedicated extended_attributes handling in `PatronMapper.java`:

Add methods `convertExtendedAttributesFromKoha(JSONArray)` and `convertExtendedAttributesToKoha(List<Object>)` as specified in spec Block C1.

In `buildPatronJson()`: Before the generic attribute loop, check for extended_attributes and handle separately:
```java
if ("extended_attributes".equals(meta.getConnIdName()) && values != null && !values.isEmpty()) {
    jo.put("extended_attributes", convertExtendedAttributesToKoha(values));
    processedKohaAttrs.add("extended_attributes");
    continue;
}
```

In `convertJsonToPatronObject()`: Before the generic attribute loop, intercept extended_attributes:
```java
if ("extended_attributes".equals(meta.getConnIdName()) && kohaJson.has("extended_attributes")) {
    Object raw = kohaJson.get("extended_attributes");
    if (raw instanceof JSONArray) {
        List<String> converted = convertExtendedAttributesFromKoha((JSONArray) raw);
        if (!converted.isEmpty()) {
            builder.addAttribute(AttributeBuilder.build("extended_attributes", converted));
        }
    }
    continue;
}
```

**Acceptance Criteria:**
- [ ] Read: Koha `[{"type":"DNI","value":"12345678"}]` → ConnId `['{"type":"DNI","value":"12345678"}']`
- [ ] Write: ConnId `['{"type":"DNI","value":"12345678"}']` → Koha `[{"type":"DNI","value":"12345678"}]`
- [ ] Invalid JSON strings are skipped with warning
- [ ] Round-trip preserves data

**Files to Modify:**
- `src/main/java/com/identicum/connectors/mappers/PatronMapper.java`

---

### Task 2.4: x-koha-embed header
**Status:** ⏳ pending
**Priority:** medium
**Depends On:** none

**Description:**
In `PatronService.java`:
- `getPatron()`: Add `request.setHeader("x-koha-embed", "extended_attributes")` before calling `callRequest()`
- `searchPatrons()`: Add same header to GET request inside the pagination loop

**Acceptance Criteria:**
- [ ] GET /patrons/{id} includes x-koha-embed header
- [ ] GET /patrons (search) includes x-koha-embed header
- [ ] Extended attributes are returned in patron response without separate API call

**Files to Modify:**
- `src/main/java/com/identicum/connectors/services/PatronService.java`

---

## Phase 3: Schema + Quality

### Task 3.1: New Koha 25.11 patron fields
**Status:** ⏳ pending
**Priority:** medium
**Depends On:** none

**Description:**
Add 17 new attributes to `PatronMapper.java` ATTRIBUTE_METADATA_MAP:

New Koha 25.x fields (10):
- preferred_name, pronouns, primary_contact_method, sms_number, middle_name, title, other_name, initials, relationship_type (all String)
- sms_provider_id (Integer)

Alternate address fields (7):
- altaddress_address, altaddress_city, altaddress_state, altaddress_postal_code, altaddress_country, altaddress_email, altaddress_phone (all String)

**Acceptance Criteria:**
- [ ] All 17 new attributes appear in connector schema
- [ ] Attributes are readable and writable (no special flags)
- [ ] Schema test passes with new attribute count

**Files to Modify:**
- `src/main/java/com/identicum/connectors/mappers/PatronMapper.java`

---

### Task 3.2: Logging guards
**Status:** ⏳ pending
**Priority:** medium
**Depends On:** none

**Description:**
In `AbstractKohaService.java`, `PatronMapper.java`, `CategoryMapper.java`:
1. Remove all "Trace Mapper:" prefixes
2. Replace `LOG.ok("...", payload.toString(2))` with guarded logging — no pretty-print
3. Never log full response bodies at OK level when they could contain PII
4. Use pattern: `LOG.ok("Executing {0} {1}", method, uri)` for flow, detailed payload only behind guard

**Acceptance Criteria:**
- [ ] No "Trace Mapper:" prefix in any log message
- [ ] No `toString(2)` (pretty-print) in log statements
- [ ] Payload logging is guarded or removed from OK/INFO level
- [ ] Tests still pass

**Files to Modify:**
- `src/main/java/com/identicum/connectors/services/AbstractKohaService.java`
- `src/main/java/com/identicum/connectors/mappers/PatronMapper.java`
- `src/main/java/com/identicum/connectors/mappers/CategoryMapper.java`

---

### Task 3.3: X-Total-Count pagination
**Status:** ⏳ pending
**Priority:** medium
**Depends On:** none

**Description:**
1. Create inner class or simple wrapper `HttpResult` in `AbstractKohaService`:
   ```java
   protected static class HttpResult {
       final String body;
       final Map<String, String> headers;
       // constructor
   }
   ```
2. Add `callRequestFull(HttpRequestBase)` method that returns `HttpResult` with body + selected headers (X-Total-Count)
3. In `PatronService.searchPatrons()` and `CategoryService.searchCategories()`: use `callRequestFull()` and read X-Total-Count:
   ```java
   if (totalCount != null) {
       moreResults = allResults.length() < totalCount;
   } else {
       moreResults = pageResults.length() == pageSize;
   }
   ```

**Acceptance Criteria:**
- [ ] Pagination stops correctly when X-Total-Count is present
- [ ] Falls back to length comparison when header is absent
- [ ] No extra request when last page has exactly pageSize items and X-Total-Count is present

**Files to Modify:**
- `src/main/java/com/identicum/connectors/services/AbstractKohaService.java`
- `src/main/java/com/identicum/connectors/services/PatronService.java`
- `src/main/java/com/identicum/connectors/services/CategoryService.java`

---

### Task 3.4: Expanded FilterTranslator
**Status:** ⏳ pending
**Priority:** medium
**Depends On:** none

**Description:**
1. `KohaFilter.java`: Add fields `matchType` (String, default "exact"), `byCategoryId` (String), `byLibraryId` (String) with getters/setters.
2. `KohaFilterTranslator.java`: Add `createContainsExpression()` and `createStartsWithExpression()` methods. Import `ContainsFilter` and `StartsWithFilter`.
3. `PatronService.searchPatrons()`: Add `_match` query param when matchType != "exact". Add `category_id` and `library_id` query params.

**Acceptance Criteria:**
- [ ] ContainsFilter on email/cardnumber/userid produces `_match=contains` query
- [ ] StartsWithFilter produces `_match=starts_with` query
- [ ] Filter by category_id and library_id works
- [ ] Existing EqualsFilter behavior unchanged

**Files to Modify:**
- `src/main/java/com/identicum/connectors/KohaFilter.java`
- `src/main/java/com/identicum/connectors/KohaFilterTranslator.java`
- `src/main/java/com/identicum/connectors/services/PatronService.java`

---

### Task 3.5: Configuration validation fix
**Status:** ⏳ pending
**Priority:** low
**Depends On:** none

**Description:**
In `KohaConfiguration.java` `validate()`, add after username check for BASIC:
```java
if (password == null) {
    throw new IllegalArgumentException("La contrasena (password) es requerida para la autenticacion BASIC.");
}
```

**Acceptance Criteria:**
- [ ] BASIC auth with null password throws IllegalArgumentException
- [ ] BASIC auth with valid password passes validation

**Files to Modify:**
- `src/main/java/com/identicum/connectors/KohaConfiguration.java`

---

## Phase 4: Tests + Docs + Release

### Task 4.1: New test classes
**Status:** ⏳ pending
**Priority:** high
**Depends On:** Tasks 2.1, 2.3, 3.5

**Description:**
Create 3 new test classes:
1. `KohaConfigurationValidationTest.java` — 6 tests (BASIC/OAUTH2 valid+invalid combinations)
2. `PatronMapperEnableTest.java` — 7 tests (computeEnabled combinations, applyEnableAttribute)
3. `ExtendedAttributesTest.java` — 6 tests (fromKoha, toKoha, invalid, null, round-trip)

**Acceptance Criteria:**
- [ ] All 19 new tests pass
- [ ] Tests are in correct package under src/test/java/com/identicum/connectors/

**Files to Create:**
- `src/test/java/com/identicum/connectors/KohaConfigurationValidationTest.java`
- `src/test/java/com/identicum/connectors/mappers/PatronMapperEnableTest.java`
- `src/test/java/com/identicum/connectors/mappers/ExtendedAttributesTest.java`

---

### Task 4.2: Update existing tests
**Status:** ⏳ pending
**Priority:** high
**Depends On:** Tasks 1.1, 1.2, 3.1, 3.4

**Description:**
1. `PatronServiceTest.java`: Update testUpdatePatron to verify HttpPatch. Add x-koha-embed test. Add X-Total-Count pagination test.
2. `CategoryServiceTest.java`: Remove create/update/delete tests. Add read-only verification.
3. `PatronMapperTest.java`: Add tests for new fields and date_enrolled CREATABLE.
4. `KohaFilterTranslatorTest.java`: Add ContainsFilter, StartsWithFilter, category_id, library_id tests.
5. `KohaConnectorIntegrationTest.java`: Add GROUP write → UnsupportedOperationException tests. Add __ENABLE__ create/update tests.

**Acceptance Criteria:**
- [ ] All existing tests pass with modifications
- [ ] New test scenarios cover all changed behavior
- [ ] `mvn test` passes with 0 failures

**Files to Modify:**
- `src/test/java/com/identicum/connectors/services/PatronServiceTest.java`
- `src/test/java/com/identicum/connectors/services/CategoryServiceTest.java`
- `src/test/java/com/identicum/connectors/mappers/PatronMapperTest.java`
- `src/test/java/com/identicum/connectors/KohaFilterTranslatorTest.java`
- `src/test/java/com/identicum/connectors/KohaConnectorIntegrationTest.java`

---

### Task 4.3: KohaResource.xml template rewrite
**Status:** ⏳ pending
**Priority:** medium
**Depends On:** Tasks 2.1, 2.3

**Description:**
Rewrite `src/main/resources/midpoint/KohaResource.xml` with:
- connectorVersion: 1.2.0
- Fixed icfc namespace
- schemaHandling with outbound mappings for: icfs:name (userid), cardnumber, surname, firstname, email, phone, category_id, library_id, expiry_date, date_of_birth, gender, statistics_1, statistics_2, sms_number
- activation section mapping __ENABLE__ via simulatedActivation
- correlation section: cardnumber (primary), userid (secondary)
- capabilities matching actual connector

**Acceptance Criteria:**
- [ ] XML is well-formed and valid
- [ ] connectorVersion matches 1.2.0
- [ ] All core outbound mappings present
- [ ] Activation configured with simulatedActivation
- [ ] Correlation configured

**Files to Modify:**
- `src/main/resources/midpoint/KohaResource.xml`

---

### Task 4.4: Documentation update
**Status:** ⏳ pending
**Priority:** low
**Depends On:** All previous tasks

**Description:**
Update README.md:
- Version 1.2.0
- PATCH requirement (Koha 25.11+)
- Extended attributes JSON format
- __ENABLE__ behavior
- Categories read-only note
- New fields list
- Changelog entry

**Acceptance Criteria:**
- [ ] README reflects v1.2.0 features
- [ ] Changelog documents all changes

**Files to Modify:**
- `README.md`

---

### Task 4.5: Build, test, and verify
**Status:** ⏳ pending
**Priority:** high
**Depends On:** All previous tasks

**Description:**
1. Run `mvn clean package` — must succeed
2. Run `mvn test` — all tests pass, 0 failures
3. Verify JAR produced: `target/connector-koha-1.2.0.jar`
4. Verify test coverage target (80%+)

**Acceptance Criteria:**
- [ ] `mvn clean package` succeeds
- [ ] All tests pass
- [ ] JAR file generated at target/connector-koha-1.2.0.jar

**Files to Modify:** None (verification only)

---

## Parallelization Strategy

Tasks that can be executed in parallel (no dependencies between them):

### Parallel Group 1 (Phase 1)
- Task 1.1: Version bump + PATCH migration
- Task 1.2: Categories read-only
- Task 1.3: Enhanced DELETE 409 handling

### Parallel Group 2 (Phase 2)
- Task 2.2: date_enrolled CREATABLE
- Task 2.3: Extended attributes serialization
- Task 2.4: x-koha-embed header

### Parallel Group 3 (Phase 3)
- Task 3.1: New Koha 25.11 fields
- Task 3.2: Logging guards
- Task 3.3: X-Total-Count pagination
- Task 3.4: Expanded FilterTranslator
- Task 3.5: Configuration validation fix

### Sequential Dependencies
1. Task 1.1 → Task 2.1 (PATCH needed before __ENABLE__ in update)
2. Tasks 2.1, 2.3, 3.5 → Task 4.1 (new tests need implementations)
3. Tasks 1.1, 1.2, 3.1, 3.4 → Task 4.2 (existing test updates)
4. All tasks → Task 4.5 (final build verification)
