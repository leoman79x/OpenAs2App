package org.openas2.app.partner;

import org.openas2.OpenAS2Exception;
import org.openas2.cmd.CommandResult;
import org.openas2.partner.PartnershipFactory;
import org.openas2.partner.StorablePartnershipFactory;

/**
 * removes a partnership entry in partnership store
 *
 * @author joseph mcverry
 */
public class DeletePartnershipCommand extends AliasedPartnershipsCommand {
    public String getDefaultDescription() {
        return "Delete the partnership associated with an name.";
    }

    public String getDefaultName() {
        return "delete";
    }

    public String getDefaultUsage() {
        return "delete <name>";
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
            ((StorablePartnershipFactory) partFx).deletePartnership(name);
            return new CommandResult(CommandResult.TYPE_OK);
        }
    }
}
