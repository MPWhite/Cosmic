package client.command.commands.gm0;

import client.Client;
import client.command.Command;
import server.population.party.KpqPartyService;

public final class KpqCommand extends Command {
    public KpqCommand() { setDescription("Find a resident party for Kerning PQ."); }
    @Override public void execute(Client client, String[] params) {
        String option = params.length == 0 ? "join" : params[0].toLowerCase(java.util.Locale.ROOT);
        switch (option) {
            case "join" -> KpqPartyService.request(client.getPlayer(), false);
            case "lead" -> KpqPartyService.request(client.getPlayer(), true);
            case "leave" -> KpqPartyService.leave(client.getPlayer());
            case "status" -> KpqPartyService.status(client.getPlayer());
            default -> client.getPlayer().dropMessage(5, "@kpq [join | lead | leave | status]");
        }
    }
}
