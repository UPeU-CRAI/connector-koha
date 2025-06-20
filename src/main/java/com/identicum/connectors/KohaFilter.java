package com.identicum.connectors;

import java.util.StringJoiner;

public class KohaFilter {

    private String byUid;
    private String byName;
    private String byEmail;
    private String byCardNumber;

    // --- Getters ---
    public String getByUid() { return byUid; }
    public String getByName() { return byName; }
    public String getByEmail() { return byEmail; }
    public String getByCardNumber() { return byCardNumber; }

    // --- Setters ---
    void setByUid(String byUid) { this.byUid = byUid; }
    void setByName(String byName) { this.byName = byName; }
    void setByEmail(String byEmail) { this.byEmail = byEmail; }
    void setByCardNumber(String byCardNumber) { this.byCardNumber = byCardNumber; }

    public boolean hasCriteria() {
        return byUid != null || byName != null || byEmail != null || byCardNumber != null; // <-- ACTUALIZAR ESTA LÍNEA
    }

    @Override
    public String toString() {
        StringJoiner joiner = new StringJoiner(", ", KohaFilter.class.getSimpleName() + "[", "]");
        if (byUid != null) joiner.add("byUid='" + byUid + "'");
        if (byName != null) joiner.add("byName='" + byName + "'");
        if (byEmail != null) joiner.add("byEmail='" + byEmail + "'");
        if (byCardNumber != null) joiner.add("byCardNumber='" + byCardNumber + "'");
        return joiner.toString();
    }
}
