package net.server.channel.handlers;

import client.Character;
import client.Client;
import client.autoban.AutobanManager;
import client.command.CommandsExecutor;
import constants.id.MapId;
import net.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import server.maps.MapleMap;
import testutil.Packets;
import tools.PacketCreator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GeneralChatVisibilityTest {
    private final GeneralChatHandler handler = new GeneralChatHandler();
    private Client client;
    private Character player;
    private MapleMap map;
    private MockedStatic<Server> server;

    @BeforeEach
    void createGm() {
        client = mock(Client.class, RETURNS_DEEP_STUBS);
        player = spy(Character.getDefault(client));
        doReturn(new AutobanManager(player)).when(player).getAutobanManager();
        player.setName("VisibilityTest");
        player.setGMLevel(2);
        map = mock(MapleMap.class);
        when(map.getId()).thenReturn(MapId.HENESYS);
        player.setMap(map);
        when(client.getPlayer()).thenReturn(player);
        when(client.tryacquireClient()).thenReturn(true);

        Server clock = mock(Server.class);
        when(clock.getCurrentTime()).thenReturn(1_000L);
        server = mockStatic(Server.class);
        server.when(Server::getInstance).thenReturn(clock);
    }

    @AfterEach
    void closeClock() {
        server.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/unhide", "@unhide", "!unhide", "/UnHide"})
    void unhidesGmThroughGeneralChat(String command) {
        player.Hide(true);
        assertTrue(player.isHidden());
        clearInvocations(client, player, map);

        sendChat(command);

        assertFalse(player.isHidden());
        verify(client).sendPacket(argThat(packet ->
                packet.equals(PacketCreator.getGMEffect(0x10, (byte) 0))));
        verify(map).broadcastSpawnPlayerMapObjectMessage(player, player, false);
        verify(player).yellowMessage("You are now visible to other players.");
        verify(client).releaseClient();
    }

    @Test
    void repeatedUnhideKeepsGmVisibleWithoutRespawningAgain() {
        player.Hide(true);
        clearInvocations(map);

        sendChat("/unhide");
        sendChat("/unhide");

        assertFalse(player.isHidden());
        verify(map).broadcastSpawnPlayerMapObjectMessage(player, player, false);
        verify(map, never()).broadcastNONGMMessage(eq(player), any(), eq(false));
    }

    @Test
    void unhideWhileAlreadyVisibleDoesNotHideGm() {
        sendChat("/unhide");

        assertFalse(player.isHidden());
        verify(map, never()).broadcastSpawnPlayerMapObjectMessage(any(), any(), anyBoolean());
        verify(player).yellowMessage("You are now visible to other players.");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void slashCommandsPreserveGmRankChecks(int rank) {
        player.setGMLevel(rank);

        sendChat("/unhide");

        verify(player).yellowMessage("You do not have permission to use this command.");
        verify(player, never()).Hide(anyBoolean());
        verify(client).releaseClient();
    }

    @Test
    void slashCommandsPreserveJailRestriction() {
        player.Hide(true);
        when(map.getId()).thenReturn(MapId.JAIL);

        sendChat("/unhide");

        assertTrue(player.isHidden());
        verify(player).yellowMessage("You do not have permission to use commands while in jail.");
    }

    @Test
    void unknownSlashCommandExplainsFailure() {
        sendChat("/missing-command");

        verify(player).yellowMessage("Command 'missing-command' is not available. See @commands for a list of available commands.");
    }

    @Test
    void recognizesPrefixesWithoutTreatingOrdinaryChatAsCommands() {
        player.setGMLevel(0);

        assertTrue(CommandsExecutor.isCommand(client, "/help"));
        assertTrue(CommandsExecutor.isCommand(client, "@help"));
        assertFalse(CommandsExecutor.isCommand(client, "!help"));
        assertFalse(CommandsExecutor.isCommand(client, "hello"));
        assertFalse(CommandsExecutor.isCommand(client, ""));
    }

    private void sendChat(String message) {
        handler.handlePacket(Packets.buildInPacket(packet -> {
            packet.writeString(message);
            packet.writeByte(1);
        }), client);
    }
}
