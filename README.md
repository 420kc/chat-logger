# Hive Chat Logger
This fork logs chat messages from RuneLite and can submit them to a Hive ingestion endpoint. It keeps the upstream per-channel files, adds an all-chat stream, and can remotely submit every `ChatMessage` event type.

The default Dylan setup is:

- `All Chat` local logging enabled.
- Public, private, friends, clan, group iron, and game chat logging enabled.
- `Remote All Chat` enabled, but remote submission only starts after an endpoint is configured.
- Log timestamps and daily rotation use `America/Chicago`.

The log file rotation happens on a daily basis and up to 30 log files are kept. However, this can be changed from the plugin's settings.

The logs can be found at RuneLite's home folder under the `chatlogs` directory. To find runelite's home navigate to `%userprofile%\.runelite` on Windows or `$HOME/.runelite` on Linux and macOS.

### Directory structure
The plugin uses the following directory structure:
```
.runelite/
└── chatlogs/
    ├── friends/
    ├── private/
    ├── public/
    ├── group/
    ├── game/
    ├── clan/
    └── all/
```

The `all` folder is the catch-all stream. It includes the raw RuneLite `ChatMessageType`, the normalized broad chat category, sender/name when present, and message text.

### Remote Submission

Configure an endpoint and Authorization value in the plugin settings. With `Remote All Chat` enabled, the plugin submits every chat message event to that endpoint. The older per-channel remote toggles still exist for selective submission, but this fork is intended to run with `Remote All Chat`.

#### Submitted payload & behavior

#### Behavior

The plugin submits chat messages every 5 seconds. Multiple chat entries can be submitted at once up to a max of 30 entries.
It also uses a circuit breaker to avoid making requests to a non-functional endpoint.
The circuit breaker opens if more than 3 requests fail within 30 seconds and will switch to half-open after 5 minutes.
Queued messages are removed only after a successful endpoint response.

**It is also worth nothing that multiple clients may submit the same message, deduplication should be done server side!**

#### Payload structure

The plugin uses the following structure to submit messages:

```json
[
  {
    "id": 1417339442575,
    "timestamp": "2021-01-01T00:00:00.000000000Z",
    "chatType": "FRIENDS",
    "chatName": "player name",
    "sender": "player name",
    "rank": -1,
    "message": "Dasdasd",
    "messageType": "FRIENDSCHAT"
  }
]
```

| field     | description                                                                                                                                                                                                                                                                                                                    |
|-----------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| id        | A message identifier that can be used to de-dupe incoming messages from multiple sources                                                                                                                                                                                                                                       |
| timestamp | An ISO-8601 compatible UTC timestamp of when the message was received by the client                                                                                                                                                                                                                                            |
| chatType  | Broad normalized category: `FRIENDS`, `CLAN`, `GROUP`, `PRIVATE`, `PUBLIC`, `GAME`, or `OTHER`                                                                                                                                                                                                                                |
| chatName  | The name of the chat that the message was sent in                                                                                                                                                                                                                                                                              |
| sender    | The player that sent the message                                                                                                                                                                                                                                                                                               |
| rank      | The ordinal representation of the sender's rank. (see [Clan Rank](https://github.com/runelite/runelite/blob/master/runelite-api/src/main/java/net/runelite/api/clan/ClanRank.java) and [Friends Chat Rank](https://github.com/runelite/runelite/blob/master/runelite-api/src/main/java/net/runelite/api/FriendsChatRank.java)) |
| message   | The message                                                                                                                                                                                                                                                                                                                    |
| messageType | Raw RuneLite `ChatMessageType` name, used by ingestion for fine-grained routing and future model labels                                                                                                                                                                                                                      |

The plugin will also always submit an `Authorization` header, the value `none` will be submitted if nothing is configured by the user.
This header **should** be used for user authentication.

### Updates

### V1.8.1
- Fixed incorrect folder mapping for game log output.

##### V1.8
- Fix compatibility with the latest client changes. The behaviour of the `rank` field might change with this update.

##### V1.6
- Fixes private logs containing clan messages

##### V1.5
- Friends/Clan rank added to remote submission

##### V1.4
- Clan chat system support
- Remote submission of clan chat messages
- Added from/to to private message logs

##### V1.2
- Added remote submission of friends chat messages

##### V1.1
- The storage directory has been changed from `clanchatlogs` to `chatlogs`
- The plugin is now capable of logging public & private chat channels. Each chat channel has its own directory inside the `chatlogs` directory 
- Each individual chat channel logging can be toggled on/off
