package org.openas2.app.partner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.openas2.OpenAS2Exception;
import org.openas2.cmd.CommandResult;
import org.openas2.partner.Partnership;
import org.openas2.partner.PartnershipFactory;
import org.openas2.partner.XMLPartnershipFactory;
import org.openas2.util.XMLUtil;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * updates an existing partnership entry in partnership store
 */
public class UpdatePartnershipCommand extends AliasedPartnershipsCommand {
    private Logger logger = LoggerFactory.getLogger(UpdatePartnershipCommand.class);

    public String getDefaultDescription() {
        return "Update an existing partnership definition in partnership store.";
    }

    public String getDefaultName() {
        return "update";
    }

    public String getDefaultUsage() {
        return "update <name> [sender=X] [receiver=Y] [attribute1=value1] [pollerConfig.attr1=value1] ...";
    }

    public CommandResult execute(PartnershipFactory partFx, Object[] params) throws OpenAS2Exception {
        if (params.length < 1) {
            return new CommandResult(CommandResult.TYPE_INVALID_PARAM_COUNT, getUsage());
        }

        synchronized (partFx) {
            String name = params[0].toString();

            // Find existing partnership
            Partnership existing = null;
            Iterator<Partnership> iter = partFx.getPartnerships().iterator();
            while (iter.hasNext()) {
                Partnership p = iter.next();
                if (p.getName().equals(name)) {
                    existing = p;
                    break;
                }
            }
            if (existing == null) {
                return new CommandResult(CommandResult.TYPE_ERROR, "Partnership not found: " + name);
            }

            // Read current sender/receiver names as defaults
            String senderName = existing.getSenderID(Partnership.PID_NAME);
            String receiverName = existing.getReceiverID(Partnership.PID_NAME);
            Element pollerConfigElem = null;

            // Parse key=value params
            for (int i = 1; i < params.length; i++) {
                String param = (String) params[i];
                int equalsPos = param.indexOf('=');
                if (equalsPos == 0) {
                    return new CommandResult(CommandResult.TYPE_ERROR, "incoming parameter missing name");
                } else if (equalsPos > 0) {
                    String key = param.substring(0, equalsPos);
                    String value = param.substring(equalsPos + 1);
                    if ("name".equals(key)) {
                        return new CommandResult(CommandResult.TYPE_ERROR, "Cannot change partnership name via update");
                    } else if ("sender".equals(key)) {
                        if (!partFx.getPartners().containsKey(value)) {
                            return new CommandResult(CommandResult.TYPE_ERROR, "Unknown sender partner: " + value);
                        }
                        senderName = value;
                    } else if ("receiver".equals(key)) {
                        if (!partFx.getPartners().containsKey(value)) {
                            return new CommandResult(CommandResult.TYPE_ERROR, "Unknown receiver partner: " + value);
                        }
                        receiverName = value;
                    } else if (param.startsWith("pollerConfig.")) {
                        String regex = "^pollerConfig.([^=]*)=((?:[^\"']+)|'(?:[^']*)'|\"(?:[^\"]*)\")";
                        Pattern p = Pattern.compile(regex);
                        Matcher m = p.matcher(param);
                        if (!m.find()) {
                            return new CommandResult(CommandResult.TYPE_ERROR, "Failed to parse pollerConfig param: " + param);
                        }
                        // Defer pollerConfig element creation until we have the doc
                        // Store raw params and process after doc creation
                        // For now, update the attribute in-memory
                        existing.setAttribute(key, value);
                    } else {
                        existing.setAttribute(key, value);
                    }
                } else {
                    return new CommandResult(CommandResult.TYPE_ERROR, "incoming parameter missing value");
                }
            }

            // Remove from in-memory list
            partFx.getPartnerships().remove(existing);

            // Delete old DOM element
            XMLPartnershipFactory xmlPartFx = (XMLPartnershipFactory) partFx;
            if (!xmlPartFx.deleteElement("/partnerships/partnership[@name='" + name + "']")) {
                return new CommandResult(CommandResult.TYPE_ERROR, "Partnership update failed: could not remove old XML element for: " + name);
            }

            // Build new DOM element
            Document doc;
            try {
                doc = XMLUtil.createDoc(null);
            } catch (Exception e) {
                throw new OpenAS2Exception(e);
            }

            Element partnershipRoot = doc.createElement("partnership");
            doc.appendChild(partnershipRoot);
            partnershipRoot.setAttribute("name", name);

            // Sender child element
            Element senderElem = doc.createElement(Partnership.PCFG_SENDER);
            senderElem.setAttribute("name", senderName);
            partnershipRoot.appendChild(senderElem);

            // Receiver child element
            Element receiverElem = doc.createElement(Partnership.PCFG_RECEIVER);
            receiverElem.setAttribute("name", receiverName);
            partnershipRoot.appendChild(receiverElem);

            // Attribute child elements from the partnership's attributes map
            for (Map.Entry<String, String> entry : existing.getAttributes().entrySet()) {
                String key = entry.getKey();
                // Skip pollerConfig entries from attributes — they go in pollerConfig element
                if (key.startsWith("pollerConfig.")) {
                    String pollerAttrName = key.substring("pollerConfig.".length());
                    if (pollerConfigElem == null) {
                        pollerConfigElem = doc.createElement("pollerConfig");
                    }
                    pollerConfigElem.setAttribute(pollerAttrName, entry.getValue());
                } else {
                    Element attrElem = doc.createElement("attribute");
                    attrElem.setAttribute("name", key);
                    attrElem.setAttribute("value", entry.getValue());
                    partnershipRoot.appendChild(attrElem);
                }
            }

            if (pollerConfigElem != null) {
                partnershipRoot.appendChild(pollerConfigElem);
            }

            // Re-load partnership from XML element (validates and re-adds to list, sets up poller)
            try {
                xmlPartFx.loadPartnership(partFx.getPartners(), partFx.getPartnerships(), partnershipRoot);
            } catch (OpenAS2Exception e) {
                logger.error(e.getMessage(), e);
                return new CommandResult(CommandResult.TYPE_ERROR, "Failed to reload updated partnership: " + e.getMessage());
            }

            // Add to DOM
            xmlPartFx.addElement(partnershipRoot);

            return new CommandResult(CommandResult.TYPE_OK);
        }
    }
}
