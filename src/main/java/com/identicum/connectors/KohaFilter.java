package com.identicum.connectors;

import java.util.StringJoiner;

public class KohaFilter {

    private String byUid;
    private String byName;
    private String byEmail;
    private String byCardNumber;
    private String matchType; // "exact" (default), "contains", "starts_with"
    private String byCategoryId;
    private String byLibraryId;

    // --- Getters ---
    public String getByUid() { return byUid; }
    public String getByName() { return byName; }
    public String getByEmail() { return byEmail; }
    public String getByCardNumber() { return byCardNumber; }
    public String getMatchType() { return matchType; }
    public String getByCategoryId() { return byCategoryId; }
    public String getByLibraryId() { return byLibraryId; }

    // --- Setters ---
    void setByUid(String byUid) { this.byUid = byUid; }
    void setByName(String byName) { this.byName = byName; }
    void setByEmail(String byEmail) { this.byEmail = byEmail; }
    void setByCardNumber(String byCardNumber) { this.byCardNumber = byCardNumber; }
    public void setMatchType(String matchType) { this.matchType = matchType; }
    public void setByCategoryId(String byCategoryId) { this.byCategoryId = byCategoryId; }
    public void setByLibraryId(String byLibraryId) { this.byLibraryId = byLibraryId; }

    public boolean hasCriteria() {
        return byUid != null || byName != null || byEmail != null || byCardNumber != null
                || byCategoryId != null || byLibraryId != null;
    }

    @Override
    public String toString() {
        StringJoiner joiner = new StringJoiner(", ", KohaFilter.class.getSimpleName() + "[", "]");
        if (byUid != null) joiner.add("byUid='" + byUid + "'");
        if (byName != null) joiner.add("byName='" + byName + "'");
        if (byEmail != null) joiner.add("byEmail='" + byEmail + "'");
        if (byCardNumber != null) joiner.add("byCardNumber='" + byCardNumber + "'");
        if (matchType != null) joiner.add("matchType='" + matchType + "'");
        if (byCategoryId != null) joiner.add("byCategoryId='" + byCategoryId + "'");
        if (byLibraryId != null) joiner.add("byLibraryId='" + byLibraryId + "'");
        return joiner.toString();
    }
}
