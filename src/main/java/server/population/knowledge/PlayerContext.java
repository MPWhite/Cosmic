package server.population.knowledge;

import client.Character;
import config.YamlConfig;

import java.util.Set;
import java.util.stream.Collectors;

/** Public gameplay facts copied before leaving the game thread. Never retains a Character or Client. */
public record PlayerContext(int playerId, String playerName, int level, int job, int mapId,
                            String mapName, Set<Integer> activeQuests, boolean autoAssignStarterStats) {
    public PlayerContext {
        playerName = bound(playerName, 16);
        mapName = bound(mapName, 80);
        activeQuests = Set.copyOf(activeQuests);
    }

    public static PlayerContext snapshot(Character player) {
        return new PlayerContext(player.getId(), player.getName(), player.getLevel(), player.getJob().getId(),
                player.getMapId(), player.getMap() == null ? "" : player.getMap().getMapName(),
                player.getStartedQuests().stream().map(q -> (int) q.getQuest().getId()).collect(Collectors.toSet()),
                YamlConfig.config.server.USE_AUTOASSIGN_STARTERS_AP);
    }

    private static String bound(String value, int limit) {
        return value == null ? "" : value.substring(0, Math.min(limit, value.length()));
    }
}
