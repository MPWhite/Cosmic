package client.command.commands.gm0;

import client.Client;
import client.command.Command;
import server.population.TownPopulation;

public final class CompanionCommand extends Command {
    public CompanionCommand() {
        setDescription("Find your companion, or send them home with @companion dismiss.");
    }

    @Override
    public void execute(Client client, String[] params) {
        if (params.length > 0 && !params[0].equalsIgnoreCase("dismiss") && !params[0].equalsIgnoreCase("status")) {
            client.getPlayer().dropMessage(5, "Use @companion for their location, or @companion dismiss to send them home.");
            return;
        }
        TownPopulation.companionStatus(client.getPlayer(), params.length > 0 && params[0].equalsIgnoreCase("dismiss"));
    }
}
