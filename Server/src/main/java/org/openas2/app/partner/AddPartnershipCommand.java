package org.openas2.app.partner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.openas2.OpenAS2Exception;
import org.openas2.cmd.CommandResult;
import org.openas2.partner.PartnershipFactory;
import org.openas2.partner.StorablePartnershipFactory;
import org.openas2.processor.sender.AS2SenderModule;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * adds a new partnership entry in partnership store
 *
 * @author joseph mcverry
 */
public class AddPartnershipCommand extends AliasedPartnershipsCommand {
    private Logger logger = LoggerFactory.getLogger(AS2SenderModule.class);

    public String getDefaultDescription() {
        return "Add a new partnership definition to partnership store.";
    }

    public String getDefaultName() {
        return "add";
    }

    public String getDefaultUsage() {
        return "add <name> <senderId> <receiverId> [attribute-1=value-1] [attribute-2=value-2] ... [attribute-n=value-n] [pollerConfig.attr1=value1 ... pollerConfig.attrn=valuen]\n"
                + "\t- subject=$receiver.name$ result entries like <attribute name=\"subject\" value=\"File $attributes.filename$ sent from $sender.name$ to $receiver.name$\"/>\n"
                + "\t- pollerConfig.enabled=true polllerConfig.filename=$properties.storageBaseDir$/outbox results in entries like <pollerConfig enabled=\"true\" filename=\"$properties.storageBaseDir$/outbox\"";
    }

    public CommandResult execute(PartnershipFactory partFx, Object[] params) throws OpenAS2Exception {
        if (params.length < 3) {
            return new CommandResult(CommandResult.TYPE_INVALID_PARAM_COUNT, getUsage());
        }

        if (!(partFx instanceof StorablePartnershipFactory)) {
            return new CommandResult(CommandResult.TYPE_COMMAND_NOT_SUPPORTED, "Not supported by current partnership store");
        }

        synchronized (partFx) {
            String name = null;
            String senderName = null;
            String receiverName = null;
            Map<String, String> attributes = new HashMap<>();
            Map<String, String> pollerConfig = new HashMap<>();

            for (int i = 0; i < params.length; i++) {
                String param = (String) params[i];
                int equalsPos = param.indexOf('=');
                if (i == 0) {
                    name = param;
                } else if (i == 1) {
                    senderName = param;
                } else if (i == 2) {
                    receiverName = param;
                } else if (equalsPos == 0) {
                    return new CommandResult(CommandResult.TYPE_ERROR, "incoming parameter missing name");
                } else if (equalsPos > 0) {
                    if (param.startsWith("pollerConfig.")) {
                        String regex = "^pollerConfig.([^=]*)=((?:[^\"']+)|'(?:[^']*)'|\"(?:[^\"]*)\")";
                        Pattern p = Pattern.compile(regex);
                        Matcher m = p.matcher(param);
                        if (!m.find()) {
                            throw new OpenAS2Exception("Failed to parse the command string: " + param);
                        }
                        pollerConfig.put(m.group(1), m.group(2));
                    } else {
                        attributes.put(param.substring(0, equalsPos), param.substring(equalsPos + 1));
                    }
                } else {
                    return new CommandResult(CommandResult.TYPE_ERROR, "incoming parameter missing value");
                }
            }

            try {
                ((StorablePartnershipFactory) partFx).addPartnership(name, senderName, receiverName, attributes, pollerConfig);
            } catch (OpenAS2Exception e) {
                logger.error(e.getMessage(), e);
                return new CommandResult(CommandResult.TYPE_ERROR, "Failed to load new partnership: " + e.getMessage());
            }
            return new CommandResult(CommandResult.TYPE_OK);
        }
    }
}
