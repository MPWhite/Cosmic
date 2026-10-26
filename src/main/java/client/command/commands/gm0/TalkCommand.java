package client.command.commands.gm0;

import client.Client;
import client.command.Command;
import server.population.TownPopulation;

public final class TalkCommand extends Command {
    public TalkCommand() {
        setDescription("Talk to a nearby resident: @talk MapleMia hello");
    }

    @Override
    public void execute(Client client, String[] params) {
        if (params.length == 0) {
            client.getPlayer().dropMessage(5, "Try @talk MapleMia hello, or @residents to meet your neighbors.");
            return;
        }
        TownPopulation.talk(client.getPlayer(), params[0], params.length > 1 ? joinStringFrom(params, 1) : "hello", true);
    }
}
