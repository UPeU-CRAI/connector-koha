package com.identicum.connectors;

import org.identityconnectors.common.logging.Log;
import org.identityconnectors.framework.common.objects.Attribute;
import org.identityconnectors.framework.common.objects.AttributeUtil;
import org.identityconnectors.framework.common.objects.Name;
import org.identityconnectors.framework.common.objects.Uid;
import org.identityconnectors.framework.common.objects.filter.AbstractFilterTranslator;
import org.identityconnectors.framework.common.objects.filter.EqualsFilter;

public class KohaFilterTranslator extends AbstractFilterTranslator<KohaFilter> {

    private static final Log LOG = Log.getLog(KohaFilterTranslator.class);

    @Override
    protected KohaFilter createEqualsExpression(EqualsFilter filter, boolean not) {
        LOG.ok("createEqualsExpression, filter: {0}, not: {1}", filter, not);

        if (not) {
            LOG.ok("NOT expressions are not supported.");
            return null;
        }

        Attribute attr = filter.getAttribute();
        String attrName = attr.getName();
        String singleValue = AttributeUtil.getAsStringValue(attr);

        if (singleValue == null) {
            LOG.warn("Filter attribute '{0}' has null or no value. Cannot translate.", attrName);
            return null;
        }

        KohaFilter translatedFilter = new KohaFilter();
        boolean handled = false;

        if (Uid.NAME.equals(attrName)) { // Filtro por __UID__
            translatedFilter.setByUid(singleValue);
            LOG.ok("Translated EqualsFilter on Uid.NAME to KohaFilter.byUid: {0}", singleValue);
            handled = true;

        } else if (Name.NAME.equals(attrName)) { // Filtro por __NAME__ (que en nuestro caso es 'userid')
            translatedFilter.setByName(singleValue);
            LOG.ok("Translated EqualsFilter on Name.NAME to KohaFilter.byName: {0}", singleValue);
            handled = true;

            // --- INICIO DE LA CORRECCIÓN ---
            // Se reemplaza la referencia a la constante por el nombre del atributo en texto plano.
        } else if ("email".equals(attrName)) { // Filtro por email
            translatedFilter.setByEmail(singleValue);
            LOG.ok("Translated EqualsFilter on 'email' to KohaFilter.byEmail: {0}", singleValue);
            handled = true;

        } else if ("cardnumber".equals(attrName)) { // Filtro por cardnumber
            // Nota: Para que este filtro funcione, necesitas añadir el campo y sus getters/setters
            // en KohaFilter.java y usarlo en executeQuery en KohaConnector.java
            translatedFilter.setByCardNumber(singleValue); // Asumiendo que añades este método
            LOG.ok("Translated EqualsFilter on 'cardnumber' to KohaFilter.byCardNumber: {0}", singleValue);
            handled = true;
        }
        // --- FIN DE LA CORRECCIÓN ---

        if (handled) {
            return translatedFilter;
        }

        LOG.ok("Unsupported attribute for EqualsFilter: {0}", attrName);
        return null;
    }
}