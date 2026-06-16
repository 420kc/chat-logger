# Hive Chat Logger ingestion handoff

## Current plugin truth

Repo: `C:/Users/dylan/plugins/chat-logger`

Fork remote: `https://github.com/420kc/chat-logger.git`

Current fork goal: capture all in-game chat Dylan sees in RuneLite, preserve local daily logs, and submit batched chat entries to a Hive ingestion endpoint for feedback search, Telegram fanout, sentiment mining, and future Unsloth dataset design.

## Plugin behavior

- Local logging:
  - `All Chat` defaults on and writes every `ChatMessage` event to `.runelite/chatlogs/<rsn>/all/`.
  - Public, private, friends, clan, group iron, and game chat defaults are all on.
  - Log timestamp pattern uses `America/Chicago`.
  - Daily log rotation uses `America/Chicago`.
- Remote logging:
  - `Remote All Chat` defaults on.
  - Submission starts only when `Endpoint` is configured.
  - `Authorization` header is always sent; blank becomes `none`.
  - Batches run every 5 seconds with up to 30 entries.
  - Batches are dropped from the queue only after a successful HTTP response.
  - Circuit breaker opens after repeated failures and retries after 5 minutes.

## Endpoint contract

Accept:

```http
POST /whatever-path-dylan-configures
Authorization: <configured value or none>
Content-Type: application/json
```

Body:

```json
[
  {
    "timestamp": "2026-06-16T01:23:45.123456Z",
    "id": 1417339442575,
    "chatType": "CLAN",
    "chatName": "Log Chasers",
    "sender": "Some Rsn",
    "rank": 100,
    "message": "kill clog feedback text",
    "messageType": "CLAN_CHAT"
  }
]
```

Fields:

- `timestamp`: UTC receive time from the client.
- `id`: RuneLite cross-world id when available for clan/friends/group; synthetic monotonic id otherwise. Do not assume global uniqueness.
- `chatType`: broad category: `FRIENDS`, `CLAN`, `GROUP`, `PRIVATE`, `PUBLIC`, `GAME`, `OTHER`.
- `chatName`: channel name or broad fallback such as `private`, `public`, `game`, or the raw message type for `OTHER`.
- `sender`: cleaned sender name when present, otherwise channel fallback.
- `rank`: rank integer when available, `-2` when unranked or not applicable.
- `message`: message body.
- `messageType`: exact RuneLite `ChatMessageType` enum name.

## Ingestion recommendations

- Return 2xx quickly after durable write. Telegram and LLM work should be async after ack.
- Deduplicate on a compound key, not `id` alone:
  - recommended first pass: `source_client_id` or auth identity + `id` + `timestamp` + `messageType` + normalized `sender` + normalized `message`.
- Store raw append-only records before any model or sentiment transforms.
- Partition by CT date for Dylan-facing browsing, but preserve UTC timestamp.
- Add fields server-side:
  - `received_at`
  - `source`
  - `auth_subject`
  - `ct_date`
  - `rsn_context` when known from auth/config
  - `telegram_delivered_at` if fanout is enabled
- Build the Hive day reader from raw records first:
  - filter by day
  - filter by `chatType`
  - search message/sender
  - flag Kill Clog / Clan Clog / Rigour Hero mentions

## Telegram fanout

Recommended: endpoint writes first, then async fanout selected events to Telegram. Start with a simple allowlist:

- private messages
- clan/friends messages containing `kill clog`, `clan clog`, `rigour`, `plugin`, `sync`, `bug`, `broken`, `feedback`

Do not make Telegram delivery part of the plugin's success path.

## Unsloth / LLM dataset lane

Do not train directly from raw chat on day one. First build a curated export:

- one JSONL row per useful feedback event or conversation slice
- include source metadata: `chatType`, `messageType`, `ct_date`, `sender_hash`, `project_tag`
- strip or hash RSNs unless Dylan explicitly wants identifiable training rows
- separate labels:
  - bug report
  - feature request
  - UX confusion
  - praise
  - support question
  - social/general

First useful model task is not generation; it is retrieval/classification over Dylan's in-game feedback.

## Current plugin acceptance command

```powershell
cd C:/Users/dylan/plugins/chat-logger
./gradlew.bat compileJava test
```
