package fking.work.chatlogger;

import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Request.Builder;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public class RemoteSubmitter {

    private static final MediaType APPLICATION_JSON = MediaType.parse("application/json");
    private static final int MAX_ENTRIES_PER_TICK = 30;
    private static final int TICK_INTERVAL = 5;
    private static final int FAILURE_THRESHOLD = 3;
    private static final long FAILURE_WINDOW_MILLIS = TimeUnit.SECONDS.toMillis(30);
    private static final long OPEN_MILLIS = TimeUnit.MINUTES.toMillis(5);

    private final ConcurrentLinkedDeque<ChatEntry> queuedEntries = new ConcurrentLinkedDeque<>();
    private final ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();

    private final ChatLoggerConfig config;
    private final OkHttpClient okHttpClient;
    private final Gson gson;
    private int failures;
    private long failureWindowStartedAt;
    private long circuitOpenUntil;

    private RemoteSubmitter(ChatLoggerConfig config, OkHttpClient okHttpClient, Gson gson) {
        this.config = config;
        this.okHttpClient = okHttpClient;
        this.gson = gson.newBuilder()
                        .registerTypeAdapter(ZonedDateTime.class, new ZonedDateTimeAdapter())
                        .create();
    }

    public static RemoteSubmitter create(ChatLoggerConfig config, OkHttpClient okHttpClient, Gson gson) {
        return new RemoteSubmitter(config, okHttpClient, gson);
    }

    public void initialize() {
        executorService.scheduleAtFixedRate(this::processQueue, TICK_INTERVAL, TICK_INTERVAL, TimeUnit.SECONDS);
    }

    public void shutdown() {
        executorService.shutdown();
    }

    public void queue(ChatEntry entry) {
        queuedEntries.add(entry);
    }

    private void processQueue() {

        if (queuedEntries.isEmpty()) {
            return;
        }
        List<ChatEntry> entries = nextBatch();
        RequestBody payload = buildPayload(entries);

        if (circuitOpen()) {
            return;
        }

        try {
            submit(payload);
            dropSubmitted(entries.size());
            resetFailures();
        } catch (Exception e) {
            recordFailure();
            log.warn("Failed to submit chat entries: {}", e.getMessage());
        }
    }

    private void submit(RequestBody payload) throws IOException {
        String authorization = config.remoteEndpointAuthorization();

        if (authorization == null || authorization.trim().isEmpty()) {
            authorization = "none";
        }
        Request request = new Builder()
                .url(config.remoteEndpoint())
                .addHeader("Authorization", authorization)
                .post(payload)
                .build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Remote endpoint returned non successful response, responseCode=" + response.code());
            }
        }
    }

    private boolean circuitOpen() {
        long now = System.currentTimeMillis();
        if (now < circuitOpenUntil) {
            return true;
        }
        if (circuitOpenUntil != 0L) {
            log.info("Checking if remote host is answering properly again (HALF_OPEN)");
            circuitOpenUntil = 0L;
        }
        return false;
    }

    private void recordFailure() {
        long now = System.currentTimeMillis();
        if (failureWindowStartedAt == 0L || now - failureWindowStartedAt > FAILURE_WINDOW_MILLIS) {
            failureWindowStartedAt = now;
            failures = 0;
        }
        failures++;
        if (failures >= FAILURE_THRESHOLD) {
            circuitOpenUntil = now + OPEN_MILLIS;
            failures = 0;
            failureWindowStartedAt = 0L;
            log.warn("Remote chat submission paused for {} minutes after repeated failures", TimeUnit.MILLISECONDS.toMinutes(OPEN_MILLIS));
        }
    }

    private void resetFailures() {
        failures = 0;
        failureWindowStartedAt = 0L;
        circuitOpenUntil = 0L;
    }

    private List<ChatEntry> nextBatch() {
        List<ChatEntry> entries = new ArrayList<>();
        int count = 0;

        for (ChatEntry entry : queuedEntries) {
            if (count >= MAX_ENTRIES_PER_TICK) {
                break;
            }
            entries.add(entry);
            count++;
        }
        return entries;
    }

    private void dropSubmitted(int size) {
        int count = 0;

        while (!queuedEntries.isEmpty() && count < size) {
            queuedEntries.poll();
            count++;
        }
    }

    private RequestBody buildPayload(List<ChatEntry> entries) {
        return RequestBody.create(APPLICATION_JSON, gson.toJson(entries));
    }
}
