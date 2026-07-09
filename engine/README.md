# engine — Arkham Horror rules engine

A headless, framework-free, **deterministic** Java 21 rules engine for a digital
*Arkham Horror: The Card Game*. It owns the authoritative `GameState` and exposes
`RulesEngine.applyIntent(...)` (validate + mutate), the skill-test **commit barrier**
(`hasPendingCommit`/`commitOptionsFor`/`resolveCommit`), and `viewFor(investigatorId)`
which produces the per-client filtered `GameStateView` that mirrors
`protocol/messages.ts`. All randomness flows through one seeded source
(`rng/SeededRng`) so play is reproducible: the same seed and the same intents always
yield the same result. The bundled `scenario/ScenarioFactory` builds the
"Spreading Flames (lite)" board ported from `prototype/index.html` (chaos bag, 8-step
`SkillTest`, hunter BFS movement, Mythos doom + encounter deck). The engine has **no
dependency on the server or any framework**.

## Test

```bash
./gradlew :engine:test
```
