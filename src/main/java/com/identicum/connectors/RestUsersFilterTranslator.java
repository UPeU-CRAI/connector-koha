package com.identicum.connectors;

import org.identityconnectors.common.logging.Log;
import org.identityconnectors.framework.common.objects.Attribute;
import org.identityconnectors.framework.common.objects.AttributeUtil;
import org.identityconnectors.framework.common.objects.Name;
import org.identityconnectors.framework.common.objects.Uid;
import org.identityconnectors.framework.common.objects.filter.AbstractFilterTranslator;
import org.identityconnectors.framework.common.objects.filter.EqualsFilter;

public class RestUsersFilterTranslator extends AbstractFilterTranslator<RestUsersFilter> {

    private static final Log LOG = Log.getLog(RestUsersFilterTranslator.class);

    @Override
    protected RestUsersFilter createEqualsExpression(EqualsFilter filter, boolean not) {
        LOG.ok("createEqualsExpression, filter: {0}, not: {1}", filter, not);

        if (not) {
            // Operaciones 'NOT' no son soportadas por este traductor.
            LOG.ok("NOT expressions are not supported.");
            return null;
        }

        Attribute attr = filter.getAttribute();
        String attrName = attr.getName();
        String singleValue = AttributeUtil.getAsStringValue(attr); // Helper para obtener el valor como String

        if (singleValue == null) {
            LOG.warn("Filter attribute '{0}' has null or no value. Cannot translate.", attrName);
            return null;
        }

        RestUsersFilter translatedFilter = new RestUsersFilter();
        boolean handled = false;

        if (Uid.NAME.equals(attrName)) { // Filtro por __UID__
            translatedFilter.setByUid(singleValue);
            LOG.ok("Translated EqualsFilter on Uid.NAME to RestUsersFilter.byUid: {0}", singleValue);
            handled = true;
        } else if (Name.NAME.equals(attrName)) { // Filtro por __NAME__
            translatedFilter.setByName(singleValue);
            LOG.ok("Translated EqualsFilter on Name.NAME to RestUsersFilter.byName: {0}", singleValue);
            handled = true;
        }
        // Si ATTR_USERID es __NAME__, el caso anterior ya lo cubre.
        // Si se quisiera permitir filtrar por ATTR_USERID *incluso si no es __NAME__*, se podría añadir:
        /*
        else if (RestUsersConnector.ATTR_USERID.equals(attrName)) {
            // Si ATTR_USERID es __NAME__, este bloque es redundante.
            // Si no, y quieres un filtro específico por ATTR_USERID:
            // translatedFilter.setBySpecificUserid(singleValue); // Necesitarías añadir este campo a RestUsersFilter
            LOG.ok("Translated EqualsFilter on ATTR_USERID to a specific filter field (if different from byName).");
            // Por ahora, asumimos que Name.NAME lo cubre si ATTR_USERID es __NAME__
        }
        */
        // Opcional: Soportar filtro por email
        else if (RestUsersConnector.ATTR_EMAIL.equals(attrName)) {
            translatedFilter.setByEmail(singleValue);
            LOG.ok("Translated EqualsFilter on ATTR_EMAIL to RestUsersFilter.byEmail: {0}", singleValue);
            handled = true;
        }
        // Puedes añadir más 'else if' para otros atributos que quieras soportar en EqualsFilter
        // ej. else if (RestUsersConnector.ATTR_CARDNUMBER.equals(attrName)) { ... }

        if (handled) {
            return translatedFilter;
        }

        LOG.ok("Unsupported attribute for EqualsFilter: {0}", attrName);
        return null; // Si el atributo no es uno de los soportados
    }

    // Aquí podrías sobrescribir otros métodos de AbstractFilterTranslator si necesitas
    // soportar otros tipos de filtro como AndFilter, OrFilter, ContainsFilter, etc.
    // Por ejemplo:
    // @Override
    // protected RestUsersFilter createContainsFilter(ContainsFilter filter, boolean not) {
    //     LOG.ok("ContainsFilter not supported by this translator.");
    //     return null;
    // }
}