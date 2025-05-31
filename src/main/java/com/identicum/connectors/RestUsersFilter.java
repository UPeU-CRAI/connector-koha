package com.identicum.connectors;

import java.util.StringJoiner;

public class RestUsersFilter {

    private String byUid;   // Para búsquedas por Uid.NAME (__UID__)
    private String byName;  // Para búsquedas por Name.NAME (__NAME__)
    private String byEmail; // Para búsquedas por el atributo de email (si se implementa)

    // --- Getters ---
    public String getByUid() {
        return byUid;
    }

    public String getByName() {
        return byName;
    }

    public String getByEmail() {
        return byEmail;
    }

    // --- Setters (o package-private para ser usado solo por el Translator) ---
    // Usaremos package-private para que solo RestUsersFilterTranslator los modifique.
    void setByUid(String byUid) {
        this.byUid = byUid;
    }

    void setByName(String byName) {
        this.byName = byName;
    }

    void setByEmail(String byEmail) {
        this.byEmail = byEmail;
    }

    /**
     * Verifica si algún criterio de filtro ha sido establecido.
     * @return true si al menos un criterio de filtro está presente, false de lo contrario.
     */
    public boolean hasCriteria() {
        return byUid != null || byName != null || byEmail != null;
    }

    @Override
    public String toString() {
        // Usar StringJoiner para una mejor construcción del string
        StringJoiner joiner = new StringJoiner(", ", RestUsersFilter.class.getSimpleName() + "[", "]");
        if (byUid != null) {
            joiner.add("byUid='" + byUid + "'");
        }
        if (byName != null) {
            joiner.add("byName='" + byName + "'");
        }
        if (byEmail != null) {
            joiner.add("byEmail='" + byEmail + "'");
        }
        return joiner.toString();
    }
}