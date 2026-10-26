# Resident knowledge and optional models

Game knowledge works without an API key. The knowledge worker reads this server's KPQ requirements, item names, positive-chance drop rows, monster spawn maps, skill descriptions, and quest-log metadata. Explorer job advancement accounts for the starter stat setting. Training guidance covers the early game and is advice, not a measured EXP/hour ranking. Unsupported or ambiguous questions get a clarification; database failures are not treated as evidence that an item never drops.

Examples: `Mia, what level for KPQ?`, `how do we do stage 2?`, `what drops slime bubble?`, `where should I train?`, `how do I get second job?`, and `what does haste do?`. A short follow-up such as `and stage 3?` or an exact item name stays with that resident/player conversation for 90 seconds. Factual replies come from the local knowledge service and bypass generated prose.

Recognizable questions about unsupported combat stats, skill duration, equipment eligibility, or effect interactions receive a local clarification instead of a generated answer. For example, `how much HP does a ligator have?` and `how long does haste last?` do not make model requests. Casual questions and banter remain social conversation.

The optional chat provider adds free-form social conversation. It receives the bot's identity/voice, public gameplay context, the current player message and at most three recent exchanges with that bot. It does not receive credentials, account records, or an entire world-state dump. The optional Jev provider selects among legal choices supplied by the party controller. Neither provider can directly move characters, grant items, read hidden puzzle combinations, clear stages, or execute commands.

## Configure

Copy `macos/resident-ai.env.example` to `.local/resident-ai.env` and fill in the providers you want. Keep that file private and out of Git. Configure a full HTTPS chat-completions endpoint, its model ID and API key, and the provider's current input/output price per million tokens. A blank key, missing endpoint/model, or zero chat prices disables paid chat. Jev uses its separate `RESIDENT_JEV_API_KEY` and pins `jev-1.14.0` by default.

The launcher automatically exports the ignored `.local/resident-ai.env` file when present. Start the server normally:

```bash
./launch-macos.command
```

The launcher never reads the tracked example directly. Restart after changing provider configuration. Environment keys are specific to residents so configuring another development tool does not accidentally enable paid game traffic. Configuration may also be provided through `cosmic.residents.ai.*` JVM properties, but environment variables avoid putting secrets in process arguments.

The default shared ceiling is $1 and 5,000 requests per server process per UTC day. Costs use the configured rates and reported usage. The service reserves a conservative maximum before each call; failed calls or responses without usage retain that reservation. Restarting the server resets these in-memory accounting counters. Set a provider-side spending limit as well if a budget must survive restarts or cover several servers.

There are two workers and a 32-task queue, a five-second request timeout, a 64 KiB response limit and a 2.5-second per-player chat/per-party decision cooldown. Overload, timeout, missing credentials, low-confidence Jev decisions, and provider failures fall back to local behavior. No automatic paid retries are made. Gameplay never waits for model completion. `ResidentIntelligence.usage()` exposes enabled flags, calls, failures, token counts and reserved/spent cost for server diagnostics without exposing keys.

## Integration contract

`PlayerContext.snapshot(character)` copies public gameplay data before leaving the game thread. `ResidentIntelligence.reply(profile, context, message, observedScene, fallback)` returns a future containing text, source and facts. Callers must verify the player, actor, map, session/visit and conversation revision still match before displaying it. Use `ResidentIntelligence.lines(reply.text())` and normal typing delays to preserve complete replies on the client's 120-character chat lines.

`decide(scopeId, observedState, legalChoices, fallback)` returns only a key from the supplied choice map. The fallback must itself be legal. Provide visible outcomes and current commitments; do not include the secret winning combination. Revalidate any returned action against the current session before using it. A decision is optional guidance; movement, inventory, NPC checks, damage and stage completion remain server code.

Tests use a loopback HTTP server and no paid API calls. Live provider quality and billing must be measured with an explicitly configured account; a passing local fixture is not evidence of a successful live Jev/chat request.

Provider references: [Jev API](https://docs.typesafe.ai/api/system-one), [choice schema](https://docs.typesafe.ai/primitives/choice), [models and pricing](https://docs.typesafe.ai/models).
