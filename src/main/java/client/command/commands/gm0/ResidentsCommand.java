package client.command.commands.gm0;

import client.Client;
import client.command.Command;
import server.population.TownPopulation;

public final class ResidentsCommand extends Command {
    public ResidentsCommand() {
        setDescription("Meet the residents of Henesys.");
    }

    @Override
    public void execute(Client client, String[] params) {
        TownPopulation.showResidents(client.getPlayer());
    }
}
