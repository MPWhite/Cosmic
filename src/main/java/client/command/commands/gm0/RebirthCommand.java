package client.command.commands.gm0;

import client.Client;
import client.command.Command;
import constants.id.NpcId;

public class RebirthCommand extends Command {
    {
        setDescription("Rebirth at level 200 or bind skills from previous jobs.");
    }

    @Override
    public void execute(Client client, String[] params) {
        client.getAbstractPlayerInteraction().openNpc(NpcId.STEWARD, "rebirth");
    }
}
