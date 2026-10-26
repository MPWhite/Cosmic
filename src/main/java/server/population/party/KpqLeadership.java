package server.population.party;

import server.population.ResidentChatInput;

import java.util.List;

/** Direct requests only: quoted commands, questions about leading and refusals do not change the party. */
enum KpqLeadership {
    NONE, HUMAN, RESIDENT;

    static KpqLeadership parse(String message, String residentName) {
        String text = ResidentChatInput.parse(message, residentName, List.of("Mia", "Pip", "Juniper")).text()
                .replaceFirst("^(?:(?:actually|hey|hi|yo|please|pls|plz) )+", "")
                .replaceFirst("(?: (?:please|pls|plz|now|thanks|thx|ty|lol))+$", "");
        if (text.matches(".*\\b(?:dont|do not|not|never|no|cant|cannot|wont|stop)\\b.*")) return NONE;
        String suffix = "(?: (?:kpq|this|this one|this run))?";
        if (text.matches("(?:(?:(?:can|could|would|will) (?:you|u) )?"
                + "(?:pass (?:me )?(?:the )?lead|give me (?:the )?lead|make me (?:the )?leader)"
                + "|(?:i|ill|i ll|i will|let me|can i) lead)" + suffix)) return HUMAN;
        if (text.matches("(?:(?:(?:can|could|would|will) )?(?:you|u) lead|take (?:the )?lead)" + suffix)) return RESIDENT;
        return NONE;
    }
}
