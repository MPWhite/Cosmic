package server.population.intelligence;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.population.ResidentProfiles;
import server.population.ResidentCatalog;
import server.population.knowledge.GameKnowledge;
import server.population.knowledge.PlayerContext;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ResidentIntelligenceTest {
    private HttpServer server;
    private ResidentIntelligence intelligence;
    private URI endpoint;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> body = new AtomicReference<>("{}");
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private static final PlayerContext PLAYER = new PlayerContext(1, "Guest", 25, 100, 103000000, "Kerning", Set.of(), true);

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/model", exchange -> {
            requests.incrementAndGet();
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/model");
    }
    @AfterEach void close() {
        if (intelligence != null) intelligence.close();
        server.stop(0);
    }
    private void configure(int calls, long interval, double dollars, boolean enabled) {
        var source = new GameKnowledge.Source() {
            @Override public Optional<GameKnowledge.KpqRules> kpqRules() { return Optional.of(new GameKnowledge.KpqRules(3, 4, 21, 30, 30)); }
            @Override public List<GameKnowledge.Item> findItems(String query) { return List.of(); }
            @Override public List<GameKnowledge.Drop> drops(int id) { return List.of(); }
        };
        var config = new ResidentIntelligence.Config(endpoint, enabled ? "test-secret" : "", "test-chat", 1, 5,
                endpoint, enabled ? "test-secret" : "", "jev-test", .042, dollars, calls, interval, Duration.ofSeconds(2));
        assertFalse(config.toString().contains("test-secret"));
        intelligence = new ResidentIntelligence(config, new GameKnowledge(source), Clock.systemUTC());
    }
    private ResidentIntelligence.Reply reply(String text) throws Exception {
        return intelligence.reply(ResidentProfiles.all().getFirst(), PLAYER, text, "Waiting in Kerning.", "authored fallback").get(4, TimeUnit.SECONDS);
    }
    private String decide() throws Exception {
        return intelligence.decide("party-1", "A teammate fell; nobody has checked the new combination.",
                Map.of("wait", "Wait for teammate", "reposition", "Move to another legal position"), "wait").get(4, TimeUnit.SECONDS);
    }

    @Test void noKeysMakesNoCallsAndFactsStillWork() throws Exception {
        configure(100, 0, 1, false);
        assertEquals("authored fallback", reply("hello").text());
        assertTrue(reply("what level for kpq").text().contains("21-30"));
        assertEquals("wait", decide());
        assertEquals(0, requests.get());
    }

    @Test void verifiedFactsBypassGenerativeModel() throws Exception {
        configure(100, 0, 1, true);
        body.set("{\"choices\":[{\"message\":{\"content\":\"KPQ is level 200\"}}]}");
        assertTrue(reply("what level for kpq").text().contains("21-30"));
        assertEquals(0, requests.get());
    }

    @Test void addressedNamesAndStageFollowupUseSameKnowledgeConversation() throws Exception {
        configure(100, 0, 1, false);
        assertTrue(reply("Mia, what level for KPQ?").text().contains("21-30"));
        assertTrue(reply("and stage 2?").text().contains("ropes"));
        var otherPlayer = new PlayerContext(2, "Other", 25, 100, 103000000, "Kerning", Set.of(), true);
        assertEquals("fallback", intelligence.reply(ResidentProfiles.all().getFirst(), otherPlayer,
                "and stage 2?", "", "fallback").get(4, TimeUnit.SECONDS).text());
    }

    @Test void chunksKeepAllWordsAndRespectClientLimit() {
        String message = "talk to Cloto for ur question, get exactly that many coupons from the crocs, trade em for a pass. drop the pass for leader. then follow everyone through the portal.";
        var lines = ResidentIntelligence.lines(message);
        assertTrue(lines.size() > 1);
        assertTrue(lines.stream().allMatch(line -> line.length() <= 120));
        assertEquals(message, String.join(" ", lines));
        assertTrue(ResidentIntelligence.lines("").isEmpty());
    }

    @Test void implicitKpqStageFollowupRemainsGroundedAndNeverCallsTheModel() throws Exception {
        configure(100, 0, 1, true);
        var context = new PlayerContext(1, "Guest", 25, 100, 103000801, "KPQ stage 2", Set.of(), true);
        var profile = ResidentProfiles.all().getFirst();
        var first = intelligence.reply(profile, context, "how do we do this stage?", "", "fallback").get(4, TimeUnit.SECONDS);
        assertTrue(first.text().contains("ropes"));
        var followup = intelligence.reply(profile, context, "and stage 3?", "", "fallback").get(4, TimeUnit.SECONDS);
        assertTrue(followup.text().contains("platforms"));
        assertEquals(0, requests.get());
    }

    @Test void recognizableUnsupportedGameQuestionsNeverReachPaidSocialGeneration() throws Exception {
        configure(100, 0, 1, true);
        body.set("{\"choices\":[{\"message\":{\"content\":\"invented game fact\"}}]}");
        for (String question : List.of("what is the max damage of double stab?", "how much HP does a ligator have?",
                "does magic guard block poison damage?", "can I equip a maple kandayo at level 25?",
                "how long does haste last?")) {
            var answer = reply(question);
            assertEquals("unsupported", answer.source(), question);
            assertTrue(answer.text().contains("can't verify"), question);
        }
        assertEquals(0, requests.get());
    }

    @Test void ordinarySocialQuestionsAndBanterStillReachOptionalChat() throws Exception {
        configure(100, 0, 1, true);
        body.set("{\"choices\":[{\"message\":{\"content\":\"lol fair\"}}]}");
        for (String social : List.of("how are you?", "why is your damage so shit lol", "can you stop missing lol")) {
            assertEquals("model", reply(social).source(), social);
        }
        assertEquals(3, requests.get());
    }

    @Test void addressedMechanicsQuestionsRemainLocalAfterGreetingAndPolitePrefixes() throws Exception {
        configure(100, 0, 1, true);
        var speaker = ResidentCatalog.find("FierySnail").orElseThrow();
        body.set("{\"choices\":[{\"message\":{\"content\":\"lol fair\"}}]}");
        for (String question : List.of("FierySnail, how much HP does a ligator have?",
                "hey FierySnail, how much HP does a ligator have?",
                "yo FierySnail, can I equip a maple kandayo at level 25?",
                "FierySnail, please how long does haste last?")) {
            var answer = intelligence.reply(speaker, PLAYER, question, "", "fallback").get(4, TimeUnit.SECONDS);
            assertEquals("unsupported", answer.source(), question);
        }
        assertEquals(0, requests.get());
        for (String social : List.of("hey FierySnail, how are you?", "FierySnail, why is your damage so shit lol",
                "yo FierySnail, can you stop missing lol")) {
            var answer = intelligence.reply(speaker, PLAYER, social, "", "fallback").get(4, TimeUnit.SECONDS);
            assertEquals("model", answer.source(), social);
        }
        assertEquals(3, requests.get());
    }

    @Test void chatTracksUsageAndBoundsOutput() throws Exception {
        configure(100, 0, 1, true);
        body.set("{\"choices\":[{\"message\":{\"content\":\"lol nice\\n  gg\"}}],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":10}}");
        assertEquals("lol nice gg", reply("that was close lol").text());
        assertEquals(100, intelligence.usage().inputTokens());
        assertEquals(10, intelligence.usage().outputTokens());
        assertEquals(.00015, intelligence.usage().reservedOrSpentUsd(), .00000001);
        assertTrue(requestBody.get().contains("max_tokens"));
        assertFalse(requestBody.get().contains("test-secret"));
    }

    @Test void jevCanOnlySelectAllowedActionsWithEnoughConfidence() throws Exception {
        configure(100, 0, 1, true);
        body.set("{\"answers\":{\"action\":{\"choice\":\"reposition\",\"confidence\":0.9}},\"usage\":{\"input_tokens\":100,\"output_tokens\":10}}");
        assertEquals("reposition", decide());
        assertTrue(requestBody.get().contains("criteria"));
        body.set("{\"answers\":{\"action\":{\"choice\":\"win_quest\",\"confidence\":1}}}");
        assertEquals("wait", decide());
        body.set("{\"answers\":{\"action\":{\"choice\":\"reposition\",\"confidence\":0.2}}}");
        assertEquals("wait", decide());
        assertThrows(IllegalArgumentException.class, () -> intelligence.decide("p", "", Map.of("wait", "Wait", "go", "Go"), "teleport"));
    }

    @Test void dailyCallCapAndCooldownPreventUnboundedSpend() throws Exception {
        configure(1, 0, 1, true);
        body.set("{\"choices\":[{\"message\":{\"content\":\"gg\"}}]}");
        assertEquals("gg", reply("nice").text());
        assertEquals("authored fallback", reply("again").text());
        assertEquals("wait", decide());
        assertEquals(1, requests.get());
    }

    @Test void reservesBudgetBeforeMakingPaidRequest() throws Exception {
        configure(100, 0, .000001, true);
        assertEquals("authored fallback", reply("hello").text());
        assertEquals(0, requests.get());
        assertEquals(0, intelligence.usage().calls());
    }

    @Test void malformedOversizedAndFailedResponsesFallBackWithoutRetries() throws Exception {
        configure(100, 0, 1, true);
        body.set("not-json");
        assertEquals("authored fallback", reply("hello").text());
        body.set("x".repeat(70_000));
        assertEquals("wait", decide());
        status.set(429);
        body.set("rate limited");
        assertEquals("authored fallback", reply("hello").text());
        assertEquals(3, requests.get());
        assertEquals(3, intelligence.usage().failures());
    }

    @Test void samePlayerSharesChatCooldownAcrossResidents() throws Exception {
        configure(100, 30_000, 1, true);
        body.set("{\"choices\":[{\"message\":{\"content\":\"gg\"}}]}");
        reply("nice");
        var other = intelligence.reply(ResidentProfiles.all().get(1), PLAYER, "nice", "", "fallback").get(4, TimeUnit.SECONDS);
        assertEquals("fallback", other.text());
        assertEquals(1, requests.get());
    }

    @Test void closedServiceCompletesFallbackInsteadOfHanging() throws Exception {
        configure(100, 0, 1, true);
        intelligence.close();
        assertEquals("authored fallback", reply("hello").text());
        assertEquals(0, requests.get());
    }

    @Test void remotePlainHttpAndCredentialUrisAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ResidentIntelligence.Config(
                URI.create("http://example.com/chat"), "secret", "chat", 1, 1, endpoint, "", "jev", .042, 1, 10, 0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new ResidentIntelligence.Config(
                URI.create("https://secret@example.com/chat"), "secret", "chat", 1, 1, endpoint, "", "jev", .042, 1, 10, 0, Duration.ofSeconds(1)));
    }
}
