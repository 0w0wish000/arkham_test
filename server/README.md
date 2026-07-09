# server — Arkham WebSocket game server

A Spring Boot (3.x, Java 21) authoritative game server that hosts the pure-Java
`engine` and exposes it over WebSocket. Each connected client sends **intents** and
**choice responses** and receives its own filtered `STATE`, plus `EVENT` /
`CHOICE_REQUEST` / `ERROR` / `PONG` messages — the exact contract in
`protocol/messages.ts`.

## Run

```bash
./gradlew :server:bootRun
```

The server listens on port `8080`; clients connect at:

```
ws://localhost:8080/ws/game
```

## Protocol quick reference

Client → Server (JSON text frames, discriminated by `type`):

- `{ "type":"JOIN", "sessionId":"room1", "investigatorId":"joe_diamond" }`
- `{ "type":"INTENT", "action":"MOVE", "payload":{ "toLocationId":"dormitories" } }`
- `{ "type":"CHOICE_RESPONSE", "requestId":"…", "choice":{ "committedCardIds":["c2"] } }`
- `{ "type":"PING" }`

Server → Client: `STATE` (per-client `GameStateView`), `EVENT`, `CHOICE_REQUEST`
(`kind:"COMMIT_CARDS"` during a skill test — the multi-client commit barrier, docs/05
§2.1), `ERROR`, `PONG`.

A room is created on first JOIN by `sessionId`. The bundled scenario seats two
same-location investigators (`joe_diamond`, `daniela`) so the commit barrier and
per-client view filtering are exercised end-to-end.
