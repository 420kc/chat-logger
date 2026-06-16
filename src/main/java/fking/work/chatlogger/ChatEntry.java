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

    private ChatEntry(long id, ChatType chatType, String chatName, String sender, int rank, String message, String messageType) {
        this.id = id;
        this.chatType = chatType;
        this.timestamp = ZonedDateTime.now(Clock.systemUTC());
        this.chatName = chatName;
        this.sender = sender;
        this.rank = rank;
        this.message = message;
        this.messageType = messageType;
    }

    public static ChatEntry from(long messageId, ChatType chatType, String chatName, int rank, ChatMessage chatMessage) {
        String name = chatMessage.getName();
        String sender = name == null || name.isEmpty() ? chatName : Text.removeFormattingTags(name);
        String messageType = chatMessage.getType() == null ? "" : chatMessage.getType().name();
        return new ChatEntry(messageId, chatType, Text.standardize(chatName), sender, rank, chatMessage.getMessage(), messageType);
    }

    public enum ChatType {
        FRIENDS,
        CLAN,
        GROUP,
        PRIVATE,
        PUBLIC,
        GAME
    }
}
