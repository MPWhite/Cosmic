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
        int selectedPriority = -1;
        for (String message : messages) {
            var intent = ResidentChatInput.parse(message, name, List.of("Mia", "Pip", "Juniper")).intent();
            int priority = switch (intent) {
                case GREETING, THANKS, LAUGH, GRATS -> 0;
                // An affirmative can accept a pending companion offer. Trailing laughter must not erase it.
                case ACK -> 1;
                default -> 2;
            };
            if (priority >= selectedPriority) {
                selected = message;
                selectedPriority = priority;
            }
        }
        return selected == null ? "hey" : selected;
    }
}
