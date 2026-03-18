package org.openas2.app.partner;

import org.openas2.OpenAS2Exception;
import org.openas2.cmd.CommandResult;
import org.openas2.partner.PartnershipFactory;
import org.openas2.partner.XMLPartnershipFactory;
import org.openas2.util.XMLUtil;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

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

        synchronized (partFx) {
            String name = params[0].toString();

            @SuppressWarnings("unchecked")
            Map<String, String> partner = (Map<String, String>) partFx.getPartners().get(name);
            if (partner == null) {
                return new CommandResult(CommandResult.TYPE_ERROR, "Unknown partner name: " + name);
            }

            // Parse key=value params and merge into existing partner map
            for (int i = 1; i < params.length; i++) {
                String param = (String) params[i];
                int pos = param.indexOf('=');
                if (pos == 0) {
                    return new CommandResult(CommandResult.TYPE_ERROR, "incoming parameter missing name");
                } else if (pos > 0) {
                    String key = param.substring(0, pos);
                    if ("name".equals(key)) {
                        return new CommandResult(CommandResult.TYPE_ERROR, "Cannot change partner name via update");
                    }
                    partner.put(key, param.substring(pos + 1));
                } else {
                    return new CommandResult(CommandResult.TYPE_ERROR, "incoming parameter missing value");
                }
            }

            // Delete old DOM element and rebuild from updated in-memory state
            XMLPartnershipFactory xmlPartFx = (XMLPartnershipFactory) partFx;
            if (!xmlPartFx.deleteElement("/partnerships/partner[@name='" + name + "']")) {
                return new CommandResult(CommandResult.TYPE_ERROR, "Partner update failed: could not remove old XML element for: " + name);
            }

            Document doc;
            try {
                doc = XMLUtil.createDoc(null);
            } catch (Exception e) {
                throw new OpenAS2Exception(e);
            }

            Element partnerRoot = doc.createElement("partner");
            doc.appendChild(partnerRoot);
            for (Map.Entry<String, String> entry : partner.entrySet()) {
                partnerRoot.setAttribute(entry.getKey(), entry.getValue());
            }

            xmlPartFx.addElement(partnerRoot);

            return new CommandResult(CommandResult.TYPE_OK);
        }
    }
}
