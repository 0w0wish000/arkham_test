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
`SkillTest`, hunter BFS movement, Mythos doom + encounter deck), shuffles every deck and
deals the opening hands. The engine has **no dependency on the server or any framework**.

## What the rules layer covers

- **Round:** mythos (skipped round 1) → investigation → enemy → upkeep. The investigation
  phase runs one investigator at a time and the **team picks the order** (docs/05 §4.1):
  anyone who has not acted may take the turn while nobody else is mid-turn.
- **Actions (3/turn):** draw, gain resource, play, activate, move, investigate, engage,
  fight, evade, parley, resign. Everything **except fight/evade/parley/resign** provokes an
  attack of opportunity.
- **Player deck:** opening hand of 5 (weaknesses set aside and redrawn), a one-time
  mulligan, drawing from an empty deck costs 1 horror and reshuffles the discard pile, and
  upkeep draws 1 / gains 1 / discards down to 8.
- **Elimination:** being defeated or resigning removes that investigator only — the game
  ends when **every** investigator is out. A resigned player still counts toward the player
  count that scales clues.
- **Doom:** the agenda advances on *all* doom in play (agenda + locations + enemies), and
  advancing discards every doom token.

Not modelled yet: activated abilities and parley text (both actions are wired but no card
carries the data), act/agenda **decks** (one of each), assets soaking damage/horror,
encounter keywords (surge/peril), victory points and campaign mode.

## Test

```bash
./gradlew :engine:test
```
