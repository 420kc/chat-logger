package fking.work.chatlogger;

import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.inject.Inject;
import com.google.inject.Provides;

import ch.qos.logback.classic.*;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy;
import fking.work.chatlogger.ChatEntry.ChatType;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.clan.ClanChannel;
import net.runelite.api.clan.ClanChannelMember;
import net.runelite.api.events.*;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.Text;
import okhttp3.OkHttpClient;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@PluginDescriptor(name = "Hive Chat Logger", description = "Logs chat messages to a file and submits them to Hive")
public class ChatLoggerPlugin extends Plugin {

    private static final String BASE_DIRECTORY = RuneLite.RUNELITE_DIR + "/chatlogs/";
    private static final int CHANNEL_UNRANKED = -2;

    @Inject
    private ChatLoggerConfig config;

    @Inject
    private Client client;

    @Inject
    private OkHttpClient httpClient;

    @Inject
    private Gson gson;

    private RemoteSubmitter remoteSubmitter;
    private final AtomicLong localMessageIds = new AtomicLong(System.currentTimeMillis());
    private Logger allChatLogger;
    private Logger publicChatLogger;
    private Logger privateChatLogger;
    private Logger friendsChatLogger;
    private Logger clanChatLogger;
    private Logger groupChatLogger;
    private Logger gameChatLogger;

    private boolean can_load = false;

    @Provides
    ChatLoggerConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(ChatLoggerConfig.class);
    }

    @Override
    protected void startUp() {
        startRemoteSubmitter();
        // If plugin enabled while logged in
        if(client.getGameState().equals(GameState.LOGGED_IN)){
            triggerInit();
        }
    }

    @Override
    protected void shutDown() {
        shutdownRemoteSubmitter();
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState().equals(GameState.LOGGED_IN)) {
            // SO this actually fires BEFORE the player is fully logged in.. sooo we know it
            // is about to happen
            triggerInit();
        }
    }

    @Subscribe
    public void onGameTick(GameTick tick) {
        // If we are logging per player, wait until we have the player login name
        if (can_load && (!config.logChatPerUser() || client.getLocalPlayer().getName() != null)) {
            initLoggers();
            can_load = false;
        }
    }

    private void triggerInit() {
        can_load = true;
    }

    private void initLoggers() {
        allChatLogger = setupLogger("AllChatLogger", "all");
        publicChatLogger = setupLogger("PublicChatLogger", "public");
        privateChatLogger = setupLogger("PrivateChatLogger", "private");
        friendsChatLogger = setupLogger("FriendsChatLogger", "friends");
        clanChatLogger = setupLogger("ClanChatLogger", "clan");
        groupChatLogger = setupLogger("GroupChatLogger", "group");
        gameChatLogger = setupLogger("GameChatLogger", "game");
    }

    private void startRemoteSubmitter() {
        if (!remoteSubmissionEnabled() || !remoteEndpointConfigured()) {
            shutdownRemoteSubmitter();
            return;
        }

        if (remoteSubmitter != null) {
            return;
        }

        log.debug("Starting a new remoteSubmitter...");
        remoteSubmitter = RemoteSubmitter.create(config, httpClient, gson);
        remoteSubmitter.initialize();
    }

    private boolean remoteSubmissionEnabled() {
        return config.remoteSubmitLogAllChat()
            || config.remoteSubmitLogFriendsChat()
            || config.remoteSubmitLogClanChat()
            || config.remoteSubmitLogGroupChat()
            || config.remoteSubmitLogPrivateChat()
            || config.remoteSubmitLogPublicChat()
            || config.remoteSubmitLogGameChat();
    }

    private boolean remoteEndpointConfigured() {
        String endpoint = config.remoteEndpoint();
        return endpoint != null && !endpoint.trim().isEmpty();
    }

    private void shutdownRemoteSubmitter() {
        if (remoteSubmitter != null) {
            remoteSubmitter.shutdown();
            remoteSubmitter = null;
        }
    }

    private int friendsChatMemberRank(String name) {
        if (name == null || name.trim().isEmpty()) {
            return CHANNEL_UNRANKED;
        }

        FriendsChatManager friendsChatManager = client.getFriendsChatManager();
        if (friendsChatManager != null) {
            FriendsChatMember member = friendsChatManager.findByName(Text.removeTags(name));
            return member != null ? member.getRank().getValue() : CHANNEL_UNRANKED;
        }
        return CHANNEL_UNRANKED;
    }

    private int clanChannelMemberRank(String name, String clanName) {
        if (name == null || name.trim().isEmpty()) {
            return CHANNEL_UNRANKED;
        }

        String cleanName = Text.removeTags(name);
        ClanChannel clanChannel = client.getClanChannel();

        if (clanChannel != null) {
            ClanChannelMember member = clanChannel.findMember(cleanName);
            if (member != null && clanChannel.getName().equals(clanName)) {
                return member.getRank().getRank();
            }
        }
        clanChannel = client.getGuestClanChannel();

        if (clanChannel != null) {
            ClanChannelMember member = clanChannel.findMember(cleanName);
            if (member != null && clanChannel.getName().equals(clanName)) {
                return member.getRank().getRank();
            }
        }
        return CHANNEL_UNRANKED;
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (!ChatLoggerConfig.GROUP_NAME.equals(event.getGroup())) {
            return;
        }
        // If we need to reload loggers
        if (event.getKey().equals("per_user") || event.getKey().equals("archive_count")) {
            triggerInit();
        }
        startRemoteSubmitter();
    }

    @Subscribe
    public void onChatMessage(ChatMessage event) {
        if (config.logAllChat() && allChatLogger != null) {
            allChatLogger.info("{} [{}] {}: {}", chatTypeName(event), classifiedChatType(event), cleanName(event), safeMessage(event));
        }

        boolean remoteAll = config.remoteSubmitLogAllChat();
        if (event.getType() == null) {
            if (shouldSubmit(remoteAll, false)) {
                submitToRemote("UNKNOWN", ChatType.OTHER, event, CHANNEL_UNRANKED);
            }
            return;
        }

        switch (event.getType()) {
            case CLAN_GIM_CHAT:
            case CLAN_GIM_MESSAGE:
            case CLAN_GIM_FORM_GROUP:
            case CLAN_GIM_GROUP_WITH:
                if (config.logGroupChat() && groupChatLogger != null) {
                    if (event.getType() == ChatMessageType.CLAN_GIM_MESSAGE) {
                        groupChatLogger.info("{}", event.getMessage());
                    } else {
                        groupChatLogger.info("{}: {}", event.getName(), event.getMessage());
                    }
                }
                
                if (shouldSubmit(remoteAll, config.remoteSubmitLogGroupChat())) {
                    submitToRemote("groupiron", ChatType.GROUP, event, CHANNEL_UNRANKED);
                }
                break;

            case FRIENDSCHAT:
                if (config.logFriendsChat() && friendsChatLogger != null) {
                    friendsChatLogger.info("[{}] {}: {}", safeText(event.getSender()), cleanName(event), safeMessage(event));
                }

                if (shouldSubmit(remoteAll, config.remoteSubmitLogFriendsChat())) {
                    FriendsChatManager friendsChatManager = client.getFriendsChatManager();
                    String owner = friendsChatManager == null || friendsChatManager.getOwner() == null || friendsChatManager.getOwner().trim().isEmpty()
                        ? "friends"
                        : friendsChatManager.getOwner();
                    submitToRemote(owner, ChatType.FRIENDS, event, friendsChatMemberRank(event.getName()));
                }
                break;

            case GAMEMESSAGE:
                if (config.logGameChat() && gameChatLogger != null) {
                    gameChatLogger.info(event.getMessage());
                }

                if (shouldSubmit(remoteAll, config.remoteSubmitLogGameChat())) {
                    submitToRemote("game", ChatType.GAME, event, CHANNEL_UNRANKED);
                }
                break;
            case CLAN_CHAT:
            case CLAN_GUEST_CHAT:
            case CLAN_MESSAGE:
                if (config.logClanChat() && clanChatLogger != null) {
                    if (event.getType() == ChatMessageType.CLAN_MESSAGE) {
                        clanChatLogger.info("{}", event.getMessage());
                    } else {
                        clanChatLogger.info("{}: {}", event.getName(), event.getMessage());
                    }
                }

                if (shouldSubmit(remoteAll, config.remoteSubmitLogClanChat())) {
                    ClanChannel clanChannel = event.getType() == ChatMessageType.CLAN_CHAT || event.getType() == ChatMessageType.CLAN_MESSAGE ? client.getClanChannel() : client.getGuestClanChannel();
                    String chatName = clanChannel == null ? "clan" : clanChannel.getName();
                    submitToRemote(chatName, ChatType.CLAN, event, clanChannelMemberRank(event.getName(), chatName));
                }
                break;
            case PRIVATECHAT:
            case MODPRIVATECHAT:
            case PRIVATECHATOUT:
                if (config.logPrivateChat() && privateChatLogger != null) {
                    String predicate = event.getType() == ChatMessageType.PRIVATECHATOUT ? "To" : "From";
                    privateChatLogger.info("{} {}: {}", predicate, cleanName(event), safeMessage(event));
                }

                if (shouldSubmit(remoteAll, config.remoteSubmitLogPrivateChat())) {
                    submitToRemote("private", ChatType.PRIVATE, event, CHANNEL_UNRANKED);
                }
                break;
            case MODCHAT:
            case PUBLICCHAT:
                if (config.logPublicChat() && publicChatLogger != null) {
                    publicChatLogger.info("{}: {}", cleanName(event), safeMessage(event));
                }

                if (shouldSubmit(remoteAll, config.remoteSubmitLogPublicChat())) {
                    submitToRemote("public", ChatType.PUBLIC, event, CHANNEL_UNRANKED);
                }
                break;
            default:
                if (shouldSubmit(remoteAll, false)) {
                    submitToRemote(chatTypeName(event), ChatType.OTHER, event, CHANNEL_UNRANKED);
                }
                break;
        }
    }

    private boolean shouldSubmit(boolean remoteAll, boolean channelEnabled) {
        return remoteSubmitter != null && (remoteAll || channelEnabled);
    }

    private void submitToRemote(String channelName, ChatType chatType, ChatMessage event, int rank) {
        long messageId = messageIdFor(chatType);
        remoteSubmitter.queue(ChatEntry.from(messageId, chatType, channelName, rank, event, localPlayerName()));
    }

    private String localPlayerName() {
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null || localPlayer.getName() == null) {
            return "";
        }
        return Text.removeFormattingTags(localPlayer.getName()).trim();
    }

    private ChatType classifiedChatType(ChatMessage event) {
        if (event.getType() == null) {
            return ChatType.OTHER;
        }

        switch (event.getType()) {
            case CLAN_GIM_CHAT:
            case CLAN_GIM_MESSAGE:
            case CLAN_GIM_FORM_GROUP:
            case CLAN_GIM_GROUP_WITH:
                return ChatType.GROUP;
            case FRIENDSCHAT:
                return ChatType.FRIENDS;
            case GAMEMESSAGE:
                return ChatType.GAME;
            case CLAN_CHAT:
            case CLAN_GUEST_CHAT:
            case CLAN_MESSAGE:
                return ChatType.CLAN;
            case PRIVATECHAT:
            case MODPRIVATECHAT:
            case PRIVATECHATOUT:
                return ChatType.PRIVATE;
            case MODCHAT:
            case PUBLICCHAT:
                return ChatType.PUBLIC;
            default:
                return ChatType.OTHER;
        }
    }

    private String chatTypeName(ChatMessage event) {
        return event.getType() == null ? "UNKNOWN" : event.getType().name();
    }

    private String cleanName(ChatMessage event) {
        String name = event.getName();
        if (name == null || name.trim().isEmpty()) {
            return "-";
        }
        return Text.removeFormattingTags(name);
    }

    private String safeMessage(ChatMessage event) {
        return safeText(event.getMessage());
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }

    private long messageIdFor(ChatType chatType) {
        if (chatType == ChatType.CLAN || chatType == ChatType.FRIENDS || chatType == ChatType.GROUP) {
            try {
                long id = CrossWorldMessages.latestId(client);
                if (id != 0) {
                    return id;
                }
            } catch (RuntimeException ex) {
                log.debug("Could not read cross-world message id", ex);
            }
        }
        return localMessageIds.incrementAndGet();
    }

    private Logger setupLogger(String loggerName, String subFolder) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern("%d{HH:mm:ss z,America/Chicago} %msg%n");
        encoder.start();

        String directory = BASE_DIRECTORY;

        if (config.logChatPerUser()) {
            directory += client.getLocalPlayer().getName() + "/";
        }

        directory += subFolder + "/";

        RollingFileAppender<ILoggingEvent> appender = new RollingFileAppender<>();
        appender.setFile(directory + "latest.log");
        appender.setAppend(true);
        appender.setEncoder(encoder);
        appender.setContext(context);

        TimeBasedRollingPolicy<ILoggingEvent> logFilePolicy = new TimeBasedRollingPolicy<>();
        logFilePolicy.setContext(context);
        logFilePolicy.setParent(appender);
        logFilePolicy.setFileNamePattern(directory + "chatlog_%d{yyyy-MM-dd,America/Chicago}.log");
        logFilePolicy.setMaxHistory(config.archiveCount());
        logFilePolicy.start();

        appender.setRollingPolicy(logFilePolicy);
        appender.start();

        Logger logger = context.getLogger(loggerName);
        logger.detachAndStopAllAppenders();
        logger.setAdditive(false);
        logger.setLevel(Level.INFO);
        logger.addAppender(appender);

        return logger;
    }
}
