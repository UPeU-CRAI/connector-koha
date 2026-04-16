# Koha Connector v1.2.0 — Technical Specification

**Status:** Draft
**Author:** Claude Code
**Date:** 2026-04-16
**Branch:** preflight/koha-connector-v1.2-upgrade
**Related:** [01-brainstorm.md](01-brainstorm.md), connector-koha v1.1.0, Koha 25.11.03 API, MidPoint 4.9.5

---

## Overview

Upgrade the ConnId Koha connector from v1.1.0 to v1.2.0 to achieve full compatibility with Koha 25.11.03 REST API, implement MidPoint lifecycle support (Joiner/Mover/Leaver), improve code quality (logging, pagination, tests), and provide a functional KohaResource.xml template.

## Background/Problem Statement

The connector was built targeting Koha 23.x. Koha 25.11 introduced breaking API changes (PUT→PATCH for patron updates), new fields, and the connector lacks critical MidPoint integration features (`__ENABLE__` for activation/deactivation, proper extended_attributes serialization). The UPeU IGA project requires a production-ready connector for 70K+ users across 4 Koha instances.

## Goals

- Full API compatibility with Koha 25.11.03
- MidPoint lifecycle support: Joiner (create), Mover (update), Leaver (disable/enable)
- Proper extended_attributes serialization for identity documents (DNI, passport, etc.)
- Categories as read-only (matching Koha API reality)
- Production-quality logging (no sensitive data in INFO level)
- Accurate pagination using X-Total-Count header
- Expanded filter support (ContainsFilter, StartsWithFilter)
- 80%+ test coverage
- Functional KohaResource.xml template

## Non-Goals

- SyncOp/LiveSync (delta sync from Koha → MidPoint) — deferred to v2.0
- Debarment/restriction management (Koha `restricted` field is read-only in API)
- Holds, checkouts, account endpoints (operational data, not identity)
- Java 11+ migration (maintaining Java 8 for MidPoint 4.9.x compatibility)
- Koha multi-instance unification (solved via multiple MidPoint resources)
- UPeU-specific Groovy scripts in the XML template

## Technical Dependencies

- Java 8 (source/target)
- Apache HttpClient 4.5.14 (already includes `HttpPatch`)
- org.json:20250517
- ConnId Framework 1.5.2.0
- Evolveum connector-parent 1.5.2.0
- JUnit 5.9.3, Mockito 5.2.0

---

## Detailed Design

### Architecture Changes

No architectural changes. The existing layered architecture (Connector → Service → Mapper) is preserved. Changes are contained within each layer:

```
KohaConnector          → Add __ENABLE__ handling, GROUP read-only guard
PatronService          → PUT→PATCH, x-koha-embed header, X-Total-Count pagination
CategoryService        → Remove create/update/delete methods
PatronMapper           → __ENABLE__, extended_attributes, new fields
CategoryMapper         → Add categorycode, expand attribute set
BaseMapper             → No changes (extended_attributes handled in PatronMapper)
KohaFilterTranslator   → ContainsFilter, StartsWithFilter, new filter fields
KohaFilter             → Add byCategoryId, byLibraryId, matchType
AbstractKohaService    → Enhanced DELETE 409 handling, logging guards
KohaConfiguration      → Validate password for BASIC auth
```

### Implementation Approach

Six implementation blocks (A-F) ordered by criticality. Each block is independently testable.

---

### Block A: Breaking Changes — Koha 25.11 API Compatibility

#### A1: PUT → PATCH for patron updates

**File:** `PatronService.java`

Replace `HttpPut` with `HttpPatch` in `updatePatron()`:

```java
// BEFORE (v1.1.0)
public void updatePatron(String uid, JSONObject payload) throws ConnectorException, IOException {
    HttpPut request = new HttpPut(getBaseUrl() + "/" + uid);
    callRequestWithEntity(request, payload);
}

// AFTER (v1.2.0)
public void updatePatron(String uid, JSONObject payload) throws ConnectorException, IOException {
    HttpPatch request = new HttpPatch(getBaseUrl() + "/" + uid);
    callRequestWithEntity(request, payload);
}
```

Import: `org.apache.http.client.methods.HttpPatch` (already in httpclient 4.5.14).

**File:** `AbstractKohaService.processResponseErrors()`

Add `"PATCH"` to the 404 handler condition (line 249):

```java
// BEFORE
if (request.getMethod().equals("GET") || request.getMethod().equals("PUT") || request.getMethod().equals("DELETE")) {

// AFTER
if (request.getMethod().equals("GET") || request.getMethod().equals("PUT") || request.getMethod().equals("PATCH") || request.getMethod().equals("DELETE")) {
```

#### A2: Eliminate GET-before-update in KohaConnector.update()

**File:** `KohaConnector.java`, method `update()` (lines 150-165)

Since PATCH accepts partial payloads, the pre-fetch GET is unnecessary. Replace the fetch+merge+prune logic with direct PATCH:

```java
// AFTER (v1.2.0) — simplified update for ACCOUNT
if (ObjectClass.ACCOUNT.is(oClass.getObjectClassValue())) {
    JSONObject changes = patronMapper.buildPatronJson(attrs, false);
    patronService.updatePatron(uid.getUidValue(), changes);
}
```

The `buildPatronJson(attrs, false)` already skips NOT_UPDATEABLE attributes and handles null values correctly (sets JSONObject.NULL for explicit nullification).

#### A3: Categories read-only

**File:** `CategoryService.java`

Remove methods: `createCategory()`, `updateCategory()`, `deleteCategory()`.

Keep: `getCategory()`, `searchCategories()`.

**File:** `KohaConnector.java`

In `create()`: remove the GROUP branch, throw `UnsupportedOperationException("Patron categories are read-only in Koha API")`.

In `update()`: same — throw for GROUP.

In `delete()`: same — throw for GROUP.

**File:** `CategoryMapper.java`

Add `categorycode` attribute (the alphanumeric identifier Koha uses for categories):

```java
ATTRIBUTE_METADATA_MAP.put("categorycode",
    new AttributeMetadata("categorycode", "patron_category_id", String.class, AttributeMetadata.Flags.REQUIRED));
```

Note: In Koha's API, `patron_category_id` is the alphanumeric code (e.g., "ESTUDI"), not a numeric ID. The UID for categories should map to this field.

Expand to include additional fields from Koha 25.11:
- `enrolment_fee` (String/number)
- `overdue_notice_required` (Boolean)
- `default_privacy` (String)
- `category_type` already exists

#### A4: Enhanced DELETE 409 handling

**File:** `AbstractKohaService.processResponseErrors()`

Replace the generic 409 handler with differentiated messages:

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

#### A5: KohaResource.xml version update

**File:** `src/main/resources/midpoint/KohaResource.xml`

Update `connectorVersion` from `1.0.1` to `1.2.0`.

#### A6: pom.xml version bump

**File:** `pom.xml`

Update `<version>1.1.0</version>` to `<version>1.2.0</version>`.

---

### Block B: __ENABLE__ Support for MidPoint Lifecycle

#### B1: Implement OperationalAttributes.ENABLE_NAME

**Strategy (Decision C1):** Dual mechanism.
- `patron_card_lost=true` → immediate block (override)
- If `patron_card_lost` is false/null → `expiry_date` determines active state
- Read: `__ENABLE__ = !patron_card_lost && !expired`
- Write disable: `patron_card_lost=true` + `expiry_date=yesterday`
- Write enable: `patron_card_lost=false` (expiry_date managed by separate mapping)

**File:** `KohaConnector.java`

In `schema()` method, add `__ENABLE__` to the ACCOUNT ObjectClass:

```java
// After building accountInfo, add operational attributes
ociBuilder.addAttributeInfo(OperationalAttributeInfos.ENABLE);
```

In `create()`: extract `__ENABLE__` from attrs. If false, set `patron_card_lost=true` and `expiry_date` to yesterday in the payload.

In `update()`: extract `__ENABLE__` from attrs. Apply dual logic.

In `executeQuery()` → `convertJsonToPatronObject()`: compute `__ENABLE__` from `patron_card_lost` and `expired`.

**File:** `PatronMapper.java`

Add method `applyEnableAttribute()`:

```java
public void applyEnableAttribute(JSONObject payload, Boolean enabled) {
    if (enabled == null) return;
    if (!enabled) {
        payload.put("patron_card_lost", true);
        payload.put("expiry_date", LocalDate.now().minusDays(1).toString());
    } else {
        payload.put("patron_card_lost", false);
        // expiry_date is NOT cleared here — managed by separate mapping
    }
}
```

Add method `computeEnabled()`:

```java
public boolean computeEnabled(JSONObject kohaJson) {
    boolean cardLost = kohaJson.optBoolean("patron_card_lost", false);
    boolean expired = kohaJson.optBoolean("expired", false);
    return !cardLost && !expired;
}
```

In `convertJsonToPatronObject()`: add `__ENABLE__` attribute:

```java
builder.addAttribute(OperationalAttributes.ENABLE_NAME, computeEnabled(kohaJson));
```

#### B2: Make date_enrolled CREATABLE

**File:** `PatronMapper.java`

Change the metadata for `date_enrolled` (line 96):

```java
// BEFORE
ATTRIBUTE_METADATA_MAP.put(ATTR_DATE_ENROLLED, new AttributeMetadata(ATTR_DATE_ENROLLED, "date_enrolled", String.class, AttributeMetadata.Flags.NOT_CREATABLE, AttributeMetadata.Flags.NOT_UPDATEABLE));

// AFTER — only NOT_UPDATEABLE (Koha accepts it on POST but not on PUT/PATCH)
ATTRIBUTE_METADATA_MAP.put(ATTR_DATE_ENROLLED, new AttributeMetadata(ATTR_DATE_ENROLLED, "date_enrolled", String.class, AttributeMetadata.Flags.NOT_UPDATEABLE));
```

---

### Block C: Extended Attributes Serialization

#### C1: JSON string format (Decision C4)

Each extended attribute is serialized as a JSON string in the ConnId multivalued list:

```
ConnId side (list of Strings):
  ["{"type":"DNI","value":"12345678"}", "{"type":"ORCID","value":"0000-0001-2345-6789"}"]

Koha API side (JSON array of objects):
  [{"type":"DNI","value":"12345678"}, {"type":"ORCID","value":"0000-0001-2345-6789"}]
```

**File:** `PatronMapper.java`

Add dedicated methods for extended_attributes handling:

```java
/**
 * Converts Koha extended_attributes JSONArray to ConnId multivalued String list.
 * Each element is a JSON string: {"type":"X","value":"Y"}
 */
private List<String> convertExtendedAttributesFromKoha(JSONArray kohaAttrs) {
    List<String> result = new ArrayList<>();
    for (int i = 0; i < kohaAttrs.length(); i++) {
        JSONObject attr = kohaAttrs.getJSONObject(i);
        JSONObject normalized = new JSONObject();
        normalized.put("type", attr.getString("type"));
        normalized.put("value", attr.optString("value", ""));
        result.add(normalized.toString());
    }
    return result;
}

/**
 * Converts ConnId multivalued String list to Koha extended_attributes JSONArray.
 * Each String element must be a valid JSON: {"type":"X","value":"Y"}
 */
private JSONArray convertExtendedAttributesToKoha(List<Object> connIdValues) {
    JSONArray result = new JSONArray();
    for (Object val : connIdValues) {
        if (val == null) continue;
        try {
            JSONObject parsed = new JSONObject(val.toString());
            if (parsed.has("type")) {
                result.put(parsed);
            }
        } catch (JSONException e) {
            LOG.warn("Skipping invalid extended_attribute value: {0}", val);
        }
    }
    return result;
}
```

In `buildPatronJson()`: intercept `extended_attributes` before generic processing:

```java
if ("extended_attributes".equals(meta.getConnIdName()) && values != null && !values.isEmpty()) {
    jo.put("extended_attributes", convertExtendedAttributesToKoha(values));
    processedKohaAttrs.add("extended_attributes");
    continue;
}
```

In `convertJsonToPatronObject()`: intercept `extended_attributes`:

```java
if ("extended_attributes".equals(meta.getConnIdName()) && kohaJson.has("extended_attributes")) {
    Object raw = kohaJson.get("extended_attributes");
    if (raw instanceof JSONArray) {
        List<String> converted = convertExtendedAttributesFromKoha((JSONArray) raw);
        if (!converted.isEmpty()) {
            builder.addAttribute(AttributeBuilder.build("extended_attributes", converted));
        }
    }
    continue; // Skip generic processing
}
```

#### C2: x-koha-embed header for GET requests

**File:** `PatronService.java`

In `getPatron()` and in the GET request within `searchPatrons()`, add the embed header:

```java
request.setHeader("x-koha-embed", "extended_attributes");
```

This causes Koha to include extended_attributes in the patron response, eliminating the need for a separate API call.

---

### Block D: New Koha 25.11 Fields

#### D1: New patron attributes

**File:** `PatronMapper.java`, static block

Add to `ATTRIBUTE_METADATA_MAP`:

```java
// New in Koha 25.x
ATTRIBUTE_METADATA_MAP.put("preferred_name", new AttributeMetadata("preferred_name", "preferred_name", String.class));
ATTRIBUTE_METADATA_MAP.put("pronouns", new AttributeMetadata("pronouns", "pronouns", String.class));
ATTRIBUTE_METADATA_MAP.put("primary_contact_method", new AttributeMetadata("primary_contact_method", "primary_contact_method", String.class));
ATTRIBUTE_METADATA_MAP.put("sms_number", new AttributeMetadata("sms_number", "sms_number", String.class));
ATTRIBUTE_METADATA_MAP.put("middle_name", new AttributeMetadata("middle_name", "middle_name", String.class));
ATTRIBUTE_METADATA_MAP.put("title", new AttributeMetadata("title", "title", String.class));
ATTRIBUTE_METADATA_MAP.put("other_name", new AttributeMetadata("other_name", "other_name", String.class));
ATTRIBUTE_METADATA_MAP.put("initials", new AttributeMetadata("initials", "initials", String.class));
ATTRIBUTE_METADATA_MAP.put("relationship_type", new AttributeMetadata("relationship_type", "relationship_type", String.class));
ATTRIBUTE_METADATA_MAP.put("sms_provider_id", new AttributeMetadata("sms_provider_id", "sms_provider_id", Integer.class));
```

#### D2: Alternate address fields

```java
// Alternate address
ATTRIBUTE_METADATA_MAP.put("altaddress_address", new AttributeMetadata("altaddress_address", "altaddress_address", String.class));
ATTRIBUTE_METADATA_MAP.put("altaddress_city", new AttributeMetadata("altaddress_city", "altaddress_city", String.class));
ATTRIBUTE_METADATA_MAP.put("altaddress_state", new AttributeMetadata("altaddress_state", "altaddress_state", String.class));
ATTRIBUTE_METADATA_MAP.put("altaddress_postal_code", new AttributeMetadata("altaddress_postal_code", "altaddress_postal_code", String.class));
ATTRIBUTE_METADATA_MAP.put("altaddress_country", new AttributeMetadata("altaddress_country", "altaddress_country", String.class));
ATTRIBUTE_METADATA_MAP.put("altaddress_email", new AttributeMetadata("altaddress_email", "altaddress_email", String.class));
ATTRIBUTE_METADATA_MAP.put("altaddress_phone", new AttributeMetadata("altaddress_phone", "altaddress_phone", String.class));
```

Total patron attributes after changes: 35 (existing) + 10 (new Koha 25.x) + 7 (altaddress) = **52 attributes**.

---

### Block E: Quality Improvements

#### E1: Logging guards

**Files:** `AbstractKohaService.java`, `PatronMapper.java`, `CategoryMapper.java`

Replace all `LOG.ok("... payload: {0}", payload.toString(2))` patterns with guarded logging:

```java
// BEFORE
LOG.ok("Trace Mapper: Request payload for {0} {1}: {2}", request.getMethod(), request.getURI(), payload.toString(2));

// AFTER
LOG.ok("Executing {0} request to {1}", request.getMethod(), request.getURI());
if (LOG.isOk()) {
    LOG.ok("Request payload: {0}", payload.toString());  // No pretty-print, no sensitive data check needed — ConnId controls log level
}
```

Remove all "Trace Mapper:" prefixes from log messages. Use concise messages.

Never log the full response body at OK/INFO level when it could contain PII. Log at a debug-equivalent level or truncate.

#### E2: Pagination with X-Total-Count

**Files:** `PatronService.java`, `CategoryService.java`

After each paginated GET, read the `X-Total-Count` header:

```java
// In AbstractKohaService, add a method that returns response + headers:
protected HttpResponseWithHeaders callRequestWithHeaders(HttpRequestBase request) throws ConnectorException, IOException {
    // Similar to callRequest but also returns selected headers
    // Returns: { body: String, totalCount: Integer (nullable) }
}
```

Alternative (simpler, less invasive): modify the pagination loop in PatronService/CategoryService to parse headers from the response:

```java
// In the pagination do-while loop, after getting response:
String totalCountHeader = // extract from response headers
Integer totalCount = null;
if (totalCountHeader != null) {
    try { totalCount = Integer.parseInt(totalCountHeader); } catch (NumberFormatException e) { /* ignore */ }
}

// End-of-pages detection:
if (totalCount != null) {
    moreResults = allResults.length() < totalCount;
} else {
    moreResults = pageResults.length() == pageSize; // Fallback to current logic
}
```

To access response headers, `callRequest()` needs to be refactored to return both body and headers. **Proposed approach:** add an overload `callRequestFull()` that returns a simple wrapper object `HttpResult(String body, Map<String,String> headers)`.

#### E3: Expanded FilterTranslator

**File:** `KohaFilterTranslator.java`

Add `ContainsFilter` and `StartsWithFilter` support:

```java
@Override
protected KohaFilter createContainsExpression(ContainsFilter filter, boolean not) {
    if (not) return null;
    Attribute attr = filter.getAttribute();
    String singleValue = AttributeUtil.getAsStringValue(attr);
    if (singleValue == null) return null;

    KohaFilter f = new KohaFilter();
    f.setMatchType("contains");
    // Set appropriate field based on attribute name
    if ("email".equals(attr.getName())) { f.setByEmail(singleValue); }
    else if ("cardnumber".equals(attr.getName())) { f.setByCardNumber(singleValue); }
    else if (Name.NAME.equals(attr.getName())) { f.setByName(singleValue); }
    else { return null; }
    return f;
}

@Override
protected KohaFilter createStartsWithExpression(StartsWithFilter filter, boolean not) {
    // Same pattern with matchType = "starts_with"
}
```

**File:** `KohaFilter.java`

Add fields:
- `matchType` (String): "exact" (default), "contains", "starts_with"
- `byCategoryId` (String)
- `byLibraryId` (String)

**File:** `PatronService.searchPatrons()`

Use the `matchType` field when building query params:

```java
if (filter != null && filter.getMatchType() != null && !"exact".equals(filter.getMatchType())) {
    queryParams.add("_match=" + filter.getMatchType());
}
if (filter != null && StringUtil.isNotBlank(filter.getByCategoryId())) {
    queryParams.add("category_id=" + urlEncodeUTF8(filter.getByCategoryId()));
}
if (filter != null && StringUtil.isNotBlank(filter.getByLibraryId())) {
    queryParams.add("library_id=" + urlEncodeUTF8(filter.getByLibraryId()));
}
```

#### E4: Configuration validation fix

**File:** `KohaConfiguration.java`, `validate()` method

Add password validation for BASIC:

```java
if ("BASIC".equalsIgnoreCase(authenticationMethodStrategy)) {
    if (username == null || username.trim().isEmpty()) {
        throw new IllegalArgumentException("El nombre de usuario (username) es requerido para la autenticacion BASIC.");
    }
    if (password == null) {
        throw new IllegalArgumentException("La contrasena (password) es requerida para la autenticacion BASIC.");
    }
}
```

---

### Block F: KohaResource.xml Template

#### F1: Complete generic KohaResource.xml

**File:** `src/main/resources/midpoint/KohaResource.xml`

Rewrite with:
- `connectorVersion: 1.2.0`
- `icfc` namespace fix (currently missing)
- Complete `schemaHandling` with outbound mappings for core fields:
  - `icfs:name` ↔ `$user/name` (userid)
  - `ri:cardnumber` ↔ `$user/extension/uniqueIdentifiers/universityIdCard`
  - `ri:surname` ↔ `$user/familyName`
  - `ri:firstname` ↔ `$user/givenName`
  - `ri:email` ↔ `$user/emailAddress`
  - `ri:phone` ↔ `$user/telephoneNumber`
  - `ri:category_id` (outbound only)
  - `ri:library_id` (outbound only)
  - `ri:expiry_date` (outbound only)
  - `ri:date_of_birth` (outbound only)
  - `ri:gender` (outbound only)
  - `ri:statistics_1` (outbound only)
  - `ri:statistics_2` (outbound only)
  - `ri:sms_number` (outbound only)
- `activation` section mapping `__ENABLE__` → `administrativeStatus`
- `correlation` section using `cardnumber` (primary) and `userid` (secondary)
- `capabilities` matching actual connector capabilities (no activation native — use `configured` with `simulatedActivation`)

---

### Code Structure — Files Modified

| File | Change Type | Block |
|------|-------------|-------|
| `pom.xml` | Version bump 1.1.0→1.2.0 | A |
| `KohaConnector.java` | __ENABLE__, GROUP read-only guards, simplified update | A, B |
| `KohaConfiguration.java` | Password validation for BASIC | E |
| `KohaFilter.java` | New fields: matchType, byCategoryId, byLibraryId | E |
| `KohaFilterTranslator.java` | ContainsFilter, StartsWithFilter | E |
| `services/PatronService.java` | PUT→PATCH, x-koha-embed header, X-Total-Count | A, C, E |
| `services/CategoryService.java` | Remove create/update/delete, X-Total-Count | A, E |
| `services/AbstractKohaService.java` | DELETE 409 differentiation, logging guards, PATCH in 404 handler | A, E |
| `mappers/PatronMapper.java` | __ENABLE__, extended_attributes, new fields, date_enrolled | B, C, D |
| `mappers/CategoryMapper.java` | Add categorycode, expand attributes | A |
| `src/main/resources/midpoint/KohaResource.xml` | Complete rewrite | F |

**No new files created** in the main source. New test files will be created (see Testing Strategy).

---

## Testing Strategy

### Unit Tests

#### New test classes:

1. **`KohaConfigurationValidationTest.java`** (new)
   - Test BASIC auth with null password → exception
   - Test BASIC auth with empty username → exception
   - Test OAUTH2 with null clientId → exception
   - Test invalid authenticationMethodStrategy → exception
   - Test valid BASIC config → passes
   - Test valid OAUTH2 config → passes

2. **`PatronMapperEnableTest.java`** (new)
   - Test `computeEnabled()`: patron_card_lost=false, expired=false → true
   - Test `computeEnabled()`: patron_card_lost=true, expired=false → false
   - Test `computeEnabled()`: patron_card_lost=false, expired=true → false
   - Test `computeEnabled()`: patron_card_lost=true, expired=true → false
   - Test `applyEnableAttribute(false)`: sets patron_card_lost=true, expiry_date=yesterday
   - Test `applyEnableAttribute(true)`: sets patron_card_lost=false, does NOT touch expiry_date
   - Test `applyEnableAttribute(null)`: no changes

3. **`ExtendedAttributesTest.java`** (new)
   - Test `convertExtendedAttributesFromKoha()`: valid JSONArray → list of JSON strings
   - Test `convertExtendedAttributesFromKoha()`: empty array → empty list
   - Test `convertExtendedAttributesToKoha()`: valid JSON strings → JSONArray
   - Test `convertExtendedAttributesToKoha()`: invalid JSON string → skipped with warning
   - Test `convertExtendedAttributesToKoha()`: null values → skipped
   - Test round-trip: Koha→ConnId→Koha preserves data

#### Modified existing tests:

4. **`PatronServiceTest.java`**
   - Update `testUpdatePatron()` to verify `HttpPatch` (not `HttpPut`)
   - Add test for x-koha-embed header in GET requests
   - Add pagination test with X-Total-Count header

5. **`CategoryServiceTest.java`**
   - Remove tests for create/update/delete
   - Add test verifying only GET methods exist

6. **`PatronMapperTest.java`**
   - Add tests for new Koha 25.x fields (preferred_name, sms_number, etc.)
   - Add tests for date_enrolled as CREATABLE

7. **`KohaFilterTranslatorTest.java`**
   - Add tests for ContainsFilter
   - Add tests for StartsWithFilter
   - Add tests for category_id and library_id filters

8. **`KohaConnectorIntegrationTest.java`**
   - Add test for GROUP create → UnsupportedOperationException
   - Add test for GROUP update → UnsupportedOperationException
   - Add test for GROUP delete → UnsupportedOperationException
   - Add test for __ENABLE__ in create (disabled patron)
   - Add test for __ENABLE__ in update (enable/disable)

### Integration Tests

Manual verification against Koha 25.11.03 at 192.168.12.135 (bul instance):
- Create patron via connector → verify in Koha staff UI
- Update patron via connector → verify PATCH works
- Disable patron (__ENABLE__=false) → verify patron_card_lost=true and expired in Koha
- Enable patron (__ENABLE__=true) → verify patron_card_lost=false in Koha
- Search patrons with ContainsFilter → verify results
- Read patron with extended_attributes → verify JSON string format

---

## Performance Considerations

- **Eliminating GET-before-update** saves 1 HTTP round-trip per update operation. With 70K users in reconciliation, this saves ~70K requests.
- **x-koha-embed header** eliminates separate extended_attributes fetch per patron — saves 1 request per patron read.
- **X-Total-Count pagination** eliminates 1 unnecessary trailing request per paginated search.
- **Logging guards** eliminate JSON pretty-printing overhead (toString(2)) when log level is above OK.
- New fields add ~3KB to schema metadata (one-time, cached via AtomicReference).

## Security Considerations

- Logging guards prevent PII (names, emails, document numbers) from appearing in INFO-level logs.
- `GuardedString` handling for passwords remains unchanged.
- Extended attributes may contain identity documents (DNI, passport) — these are only logged at debug level, never at INFO.
- OAuth2 token caching and refresh logic remains unchanged (already secure).

## Documentation

- Update `README.md` with:
  - New version 1.2.0
  - Note about PATCH (Koha 25.11+ requirement)
  - Extended attributes JSON format documentation
  - __ENABLE__ behavior documentation
  - Categories read-only note
  - New fields list
- Update `PRD.md` changelog section
- KohaResource.xml inline comments serve as deployment guide

---

## Implementation Phases

### Phase 1: Core API Compatibility (Block A)
- A1: PUT → PATCH
- A2: Eliminate GET-before-update
- A3: Categories read-only
- A4: DELETE 409 differentiation
- A5: KohaResource.xml version
- A6: pom.xml version bump
- **Tests:** PatronServiceTest (PATCH), CategoryServiceTest (read-only), KohaConnectorIntegrationTest (GROUP guards)

### Phase 2: Lifecycle Support (Block B + C)
- B1: __ENABLE__ implementation
- B2: date_enrolled CREATABLE
- C1: Extended attributes serialization
- C2: x-koha-embed header
- **Tests:** PatronMapperEnableTest, ExtendedAttributesTest, integration tests

### Phase 3: Expanded Schema + Quality (Block D + E)
- D1: New patron attributes (10 fields)
- D2: Alternate address fields (7 fields)
- E1: Logging guards
- E2: X-Total-Count pagination
- E3: FilterTranslator expansion
- E4: Configuration validation
- **Tests:** PatronMapperTest (new fields), KohaFilterTranslatorTest (new filters), KohaConfigurationValidationTest

### Phase 4: Resource Template + Docs (Block F)
- F1: KohaResource.xml complete rewrite
- README.md update
- Final test run, coverage verification (target: 80%)

---

## Open Questions

None — all clarifications resolved in brainstorm (C1-C8).

## References

- [Koha REST API spec (25.11)](https://api.koha-community.org/)
- [ConnId Framework documentation](https://connid.tirasa.net/)
- [MidPoint 4.9 ConnId connector development guide](https://docs.evolveum.com/connectors/connid/1.x/connector-development-guide/)
- [Apache HttpClient 4.5 — HttpPatch](https://hc.apache.org/httpcomponents-client-4.5.x/index.html)
- Brainstorm: [01-brainstorm.md](01-brainstorm.md)
