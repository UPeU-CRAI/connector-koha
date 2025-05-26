package com.identicum.connectors;

import org.identityconnectors.common.logging.Log;
import org.identityconnectors.framework.common.objects.Attribute;
import org.identityconnectors.framework.common.objects.Name;
import org.identityconnectors.framework.common.objects.Uid;
import org.identityconnectors.framework.common.objects.filter.AbstractFilterTranslator;
import org.identityconnectors.framework.common.objects.filter.EqualsFilter;

public class RestUsersFilterTranslator extends AbstractFilterTranslator<RestUsersFilter>
{
	private static final Log LOG = Log.getLog(RestUsersFilter.class);
	
	@Override
    protected RestUsersFilter createEqualsExpression(EqualsFilter filter, boolean not) {
        
		LOG.ok("createEqualsExpression, filter: {0}, not: {1}", filter, not);

        if (not) {
            return null; // not supported
        }

        Attribute attr = filter.getAttribute();
        LOG.ok("attr.getName:  {0}, attr.getValue: {1}, Uid.NAME: {2}, Name.NAME: {3}", attr.getName(), attr.getValue(), Uid.NAME, Name.NAME);
        if (Uid.NAME.equals(attr.getName())) {
            if (attr.getValue() != null && attr.getValue().get(0) != null) {
            	RestUsersFilter lf = new RestUsersFilter();
                lf.byUid = String.valueOf(attr.getValue().get(0));
                LOG.ok("lf.byUid: {0}, attr.getValue().get(0): {1}", lf.byUid, attr.getValue().get(0));
                return lf;
            }
        }
        else if (RestUsersConnector.ATTR_USERID.equals(attr.getName())) {
            if (attr.getValue() != null && attr.getValue().get(0) != null) {
            	RestUsersFilter lf = new RestUsersFilter();
                lf.byUsername = String.valueOf(attr.getValue().get(0));
                return lf;
            }
        }
        return null;
    }
}
