package org.openas2.app.partner;

import org.openas2.OpenAS2Exception;
import org.openas2.cmd.CommandResult;
import org.openas2.partner.PartnershipFactory;
import org.openas2.partner.StorablePartnershipFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * updates an existing partner entry in partnership store
 */
public class UpdatePartnerCommand extends AliasedPartnershipsCommand {
    public String getDefaultDescription() {
        return "Update an existing partner in partnership store.";
    }

    public String getDefaultName() {
        return "update";
    }

    public String getDefaultUsage() {
        return "update <name> [attribute1=value1] [attribute2=value2] ...";
    }

    public CommandResult execute(PartnershipFactory partFx, Object[] params) throws OpenAS2Exception {
        if (params.length < 1) {
            return new CommandResult(CommandResult.TYPE_INVALID_PARAM_COUNT, getUsage());
        }

        if (!(partFx instanceof StorablePartnershipFactory)) {
            return new CommandResult(CommandResult.TYPE_COMMAND_NOT_SUPPORTED, "Not supported by current partnership store");
        }

        synchronized (partFx) {
            String name = params[0].toString();

            @SuppressWarnings("unchecked")
            Map<String, String> partner = (Map<String, String>) partFx.getPartners().get(name);
            if (partner == null) {
                return new CommandResult(CommandResult.TYPE_ERROR, "Unknown partner name: " + name);
            }

            // Build merged attributes: start from existing, override with params
            Map<String, String> merged = new LinkedHashMap<>(partner);

            for (int i = 1; i < params.length; i++) {
                String param = (String) params[i];
                int pos = param.indexOf('=');
                if (pos == 0) {
                    return new CommandResult(CommandResult.TYPE_ERROR, "incoming parameter missing name");
                } else if (pos > 0) {
                    String key = param.substring(0, pos);
                    String value = param.substring(pos + 1);
                    if ("name".equals(key)) {
                        if (!name.equals(value)) {
                            return new CommandResult(CommandResult.TYPE_ERROR, "Cannot change partner name via update");
                        }
                        continue;
                    }
                    merged.put(key, value);
                } else {
                    return new CommandResult(CommandResult.TYPE_ERROR, "incoming parameter missing value");
                }
            }

            ((StorablePartnershipFactory) partFx).updatePartner(name, merged);
            return new CommandResult(CommandResult.TYPE_OK);
        }
    }
}
