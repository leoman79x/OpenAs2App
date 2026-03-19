package org.openas2.app.partner;

import org.openas2.OpenAS2Exception;
import org.openas2.cmd.CommandResult;
import org.openas2.partner.PartnershipFactory;
import org.openas2.partner.XMLPartnershipFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.FactoryConfigurationError;
import javax.xml.parsers.ParserConfigurationException;
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

            // Build new DOM element: start from existing attributes, override with params
            // This mirrors AddPartnerCommand's approach of building the element first
            DocumentBuilder db = null;
            try {
                db = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            } catch (ParserConfigurationException e) {
                throw new OpenAS2Exception(e);
            } catch (FactoryConfigurationError e) {
                throw new OpenAS2Exception(e);
            }

            Document doc = db.newDocument();
            Element partnerRoot = doc.createElement("partner");
            doc.appendChild(partnerRoot);

            // Copy all existing attributes onto the element
            for (Map.Entry<String, String> entry : partner.entrySet()) {
                partnerRoot.setAttribute(entry.getKey(), entry.getValue());
            }

            // Override with incoming params (same parsing as AddPartnerCommand)
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
                    partnerRoot.setAttribute(key, value);
                } else {
                    return new CommandResult(CommandResult.TYPE_ERROR, "incoming parameter missing value");
                }
            }

            // Replace in-memory partner map
            partFx.getPartners().remove(name);
            XMLPartnershipFactory xmlPartFx = (XMLPartnershipFactory) partFx;
            xmlPartFx.loadPartner(partFx.getPartners(), partnerRoot);

            // Replace DOM element in-place (preserves ordering before partnerships)
            if (!xmlPartFx.replaceElement("/partnerships/partner[@name='" + name + "']", partnerRoot)) {
                return new CommandResult(CommandResult.TYPE_ERROR, "Partner update failed: could not replace XML element for: " + name);
            }

            return new CommandResult(CommandResult.TYPE_OK);
        }
    }
}
