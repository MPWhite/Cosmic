package server.population;

import java.util.ArrayDeque;
import java.util.List;

/** One short typing burst. Later questions/corrections take priority over greetings and filler. */
final class ResidentChatTurn {
    private final String name;
    private final ArrayDeque<String> messages = new ArrayDeque<>();
    private long movementRevision;

    ResidentChatTurn(String name, String message) {
        this.name = name;
        append(message);
    }

    void append(String message) {
        String bounded = message.strip();
        if (bounded.isEmpty()) return;
        messages.addLast(bounded.substring(0, Math.min(120, bounded.length())));
        while (messages.size() > 3) messages.removeFirst();
    }

    void movementRevision(long revision) { movementRevision = revision; }
    long movementRevision() { return movementRevision; }

    String message() {
        String selected = messages.peekLast();
        for (String message : messages) {
            var intent = ResidentChatInput.parse(message, name, List.of("Mia", "Pip", "Juniper")).intent();
            if (switch (intent) {
                case GREETING, THANKS, ACK, LAUGH, GRATS -> false;
                default -> true;
            }) selected = message;
        }
        return selected == null ? "hey" : selected;
    }
}
