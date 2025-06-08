package com.identicum.connectors.model;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Representa los metadatos para un atributo del conector, mapeando
 * su nombre en ConnId a su nombre nativo en Koha y definiendo sus
 * características (tipo, si es requerido, multivaluado, etc.).
 */
public class AttributeMetadata {

    private final String connIdName;
    private final String kohaNativeName;
    private final Class<?> type;
    private final Set<Flags> flags;

    public enum Flags {
        REQUIRED,
        NOT_CREATABLE,
        NOT_UPDATEABLE,
        NOT_READABLE,
        MULTIVALUED
    }

    public AttributeMetadata(String connIdName, String kohaNativeName, Class<?> type, Flags... flags) {
        this.connIdName = connIdName;
        this.kohaNativeName = kohaNativeName;
        this.type = type;
        this.flags = (flags == null) ? Collections.emptySet() : new HashSet<>(Arrays.asList(flags));
    }

    public String getConnIdName() {
        return connIdName;
    }

    public String getKohaNativeName() {
        return kohaNativeName;
    }

    public Class<?> getType() {
        return type;
    }

    public Set<Flags> getFlags() {
        return flags;
    }

    public boolean isRequired() {
        return this.flags.contains(Flags.REQUIRED);
    }

    public boolean isNotCreatable() {
        return this.flags.contains(Flags.NOT_CREATABLE);
    }

    public boolean isNotUpdateable() {
        return this.flags.contains(Flags.NOT_UPDATEABLE);
    }

    public boolean isNotReadable() {
        return this.flags.contains(Flags.NOT_READABLE);
    }

    public boolean isMultivalued() {
        return this.flags.contains(Flags.MULTIVALUED);
    }
}