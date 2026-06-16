package fking.work.chatlogger;

import lombok.ToString;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.util.Text;

import java.time.Clock;
import java.time.ZonedDateTime;

@ToString
public class ChatEntry {

    private final ZonedDateTime timestamp;
    private final long id;
    private final ChatType chatType;
    private final String chatName;
    private final String sender;
    private final int rank;
    private final String message;
    private final String messageType;
    private final String clientRsn;

    private ChatEntry(long id, ChatType chatType, String chatName, String sender, int rank, String message, String messageType, String clientRsn) {
        this.id = id;
        this.chatType = chatType;
        this.timestamp = ZonedDateTime.now(Clock.systemUTC());
        this.chatName = chatName;
        this.sender = sender;
        this.rank = rank;
        this.message = message;
        this.messageType = messageType;
        this.clientRsn = clientRsn;
    }

    public static ChatEntry from(long messageId, ChatType chatType, String chatName, int rank, ChatMessage chatMessage, String clientRsn) {
        String name = chatMessage.getName();
        String fallbackName = chatName == null || chatName.trim().isEmpty() ? chatType.name().toLowerCase() : chatName;
        String sender = name == null || name.isEmpty() ? fallbackName : Text.removeFormattingTags(name);
        String messageType = chatMessage.getType() == null ? "" : chatMessage.getType().name();
        String message = chatMessage.getMessage() == null ? "" : chatMessage.getMessage();
        String cleanedClientRsn = clientRsn == null ? "" : Text.removeFormattingTags(clientRsn).trim();
        return new ChatEntry(messageId, chatType, Text.standardize(fallbackName), sender, rank, message, messageType, cleanedClientRsn);
    }

    public enum ChatType {
        FRIENDS,
        CLAN,
        GROUP,
        PRIVATE,
        PUBLIC,
        GAME,
        OTHER
    }
}
