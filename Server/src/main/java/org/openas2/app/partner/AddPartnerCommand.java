package org.openas2.app.partner;

import org.openas2.OpenAS2Exception;
import org.openas2.cmd.CommandResult;
import org.openas2.partner.PartnershipFactory;
import org.openas2.partner.StorablePartnershipFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * adds a new partner entry in partnership store
 *
 * @author joseph mcverry
 */
public class AddPartnerCommand extends AliasedPartnershipsCommand {
    public String getDefaultDescription() {
        return "Add a new partner to partnership store.";
    }

    public String getDefaultName() {
        return "add";
    }

    public String getDefaultUsage() {
        return "add name <attribute 1=value-1> <attribute 2=value-2> ... <attribute n=value-n>";
    }

    public CommandResult execute(PartnershipFactory partFx, Object[] params) throws OpenAS2Exception {
        if (params.length < 1) {
            return new CommandResult(CommandResult.TYPE_INVALID_PARAM_COUNT, getUsage());
        }

        if (!(partFx instanceof StorablePartnershipFactory)) {
            return new CommandResult(CommandResult.TYPE_COMMAND_NOT_SUPPORTED, "Not supported by current partnership store");
        }

        synchronized (partFx) {
            Map<String, String> attributes = new LinkedHashMap<>();

            for (int i = 0; i < params.length; i++) {
                String param = (String) params[i];
                int pos = param.indexOf('=');
                if (i == 0) {
                    attributes.put("name", param);
                } else if (pos == 0) {
                    return new CommandResult(CommandResult.TYPE_ERROR, "incoming parameter missing name");
                } else if (pos > 0) {
                    attributes.put(param.substring(0, pos), param.substring(pos + 1));
                } else {
                    return new CommandResult(CommandResult.TYPE_ERROR, "incoming parameter missing value");
                }
            }

            ((StorablePartnershipFactory) partFx).addPartner(attributes);
            return new CommandResult(CommandResult.TYPE_OK);
        }
    }
}
