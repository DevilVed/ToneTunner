package com.devilved.tonetunner.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Every successful (billed) request reports its usage to the dashboard meter, exactly once. */
public class CloudClientUsageTest {
    private MockWebServer server;
    private final List<Usage> transcriptions = new ArrayList<>();
    private final List<Double> audioSeconds = new ArrayList<>();
    private final List<Usage> enhancements = new ArrayList<>();
    private final List<int[]> characters = new ArrayList<>();

    @Before public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @After public void tearDown() throws IOException {
        server.shutdown();
    }

    @Test public void transcriptionReportsProviderUsageAndAudioLength() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"text\":\"hi\",\"usage\":{\"type\":\"tokens\","
                + "\"input_tokens\":50,\"output_tokens\":3,\"input_token_details\":{\"audio_tokens\":45}}}"));
        CloudClient client = client();
        client.transcribe(new byte[32_000], config(Provider.OPENAI, "gpt-4o-mini-transcribe"), "en", false);
        assertEquals(1, transcriptions.size());
        assertEquals(50, transcriptions.get(0).inputTokens);
        assertEquals(45, transcriptions.get(0).audioTokens);
        assertEquals("one second of 16 kHz PCM16", 1.0, audioSeconds.get(0), 1e-9);
    }

    @Test public void billedNoSpeechAndInvalidResponsesAreStillCounted() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"text\":\"  \"}"));
        server.enqueue(new MockResponse().setBody("not json"));
        CloudClient client = client();
        assertThrows(CloudClient.NoSpeechException.class, () ->
                client.transcribe(new byte[3200], config(Provider.GROQ, "whisper-large-v3-turbo"), "en", false));
        assertThrows(IOException.class, () ->
                client.transcribe(new byte[3200], config(Provider.GROQ, "whisper-large-v3-turbo"), "en", false));
        assertEquals(2, transcriptions.size());
        assertFalse(transcriptions.get(1).reported);
    }

    @Test public void successCancelledBeforeItIsReadIsStillCountedOnce() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"text\":\"already transcribed\",\"usage\":{\"seconds\":2}}"));
        server.enqueue(new MockResponse().setBody("{\"choices\":[{\"finish_reason\":\"stop\","
                + "\"message\":{\"content\":\"Hello.\"}}],\"usage\":{\"prompt_tokens\":30,\"completion_tokens\":2}}"));
        CloudClient[] holder = new CloudClient[1];
        holder[0] = client(chain -> {
            okhttp3.Response response = chain.proceed(chain.request());
            holder[0].cancel(); // The provider has answered (and billed) before the user cancels.
            return response;
        });
        assertThrows(IOException.class, () -> holder[0].transcribe(new byte[64_000],
                config(Provider.GROQ, "whisper-large-v3-turbo"), "en", false));
        assertEquals(1, transcriptions.size());
        assertEquals("billed by the recorded audio length", 2.0, audioSeconds.get(0), 1e-9);

        holder[0] = client(chain -> {
            okhttp3.Response response = chain.proceed(chain.request());
            holder[0].cancel();
            return response;
        });
        assertThrows(IOException.class, () ->
                holder[0].enhance("hello", config(Provider.OPENAI, "gpt-4o-mini"), "", ""));
        assertEquals(1, enhancements.size());
    }

    @Test public void httpErrorsAreNotCounted() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500));
        CloudClient client = client();
        assertThrows(IOException.class, () ->
                client.transcribe(new byte[3200], config(Provider.GROQ, "whisper-large-v3-turbo"), "en", false));
        assertTrue(transcriptions.isEmpty());
    }

    @Test public void enhancementReportsCachedTokensAndCost() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"choices\":[{\"finish_reason\":\"stop\","
                + "\"message\":{\"role\":\"assistant\",\"content\":\"Hello, world.\"}}],"
                + "\"usage\":{\"prompt_tokens\":300,\"completion_tokens\":5,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":256},\"cost\":0.0004}}"));
        CloudClient client = client();
        client.enhance("hello world", config(Provider.OPENROUTER, "openai/gpt-4o-mini"), "", "");
        assertEquals(1, enhancements.size());
        Usage usage = enhancements.get(0);
        assertEquals(256, usage.cachedTokens);
        assertEquals(0.0004, usage.reportedCostUsd, 1e-12);
        assertEquals("Hello, world.".length(), characters.get(0)[1]);
        assertTrue(characters.get(0)[0] > "hello world".length());
    }

    @Test public void truncatedEnhancementIsCountedEvenThoughItFails() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"choices\":[{\"finish_reason\":\"length\","
                + "\"message\":{\"content\":\"Hel\"}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":4096}}"));
        CloudClient client = client();
        assertThrows(IOException.class, () ->
                client.enhance("hello world", config(Provider.OPENAI, "gpt-4o-mini"), "", ""));
        assertEquals(1, enhancements.size());
        assertEquals(4096, enhancements.get(0).outputTokens);
        assertEquals(0, characters.get(0)[1]);
    }

    @Test public void aThrowingListenerNeverFailsTheRequest() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"text\":\"hi\"}"));
        CloudClient client = client();
        client.setUsageListener(new Usage.Listener() {
            @Override public void onTranscription(Usage usage, long elapsedMs, double seconds) {
                throw new IllegalStateException("metrics failure");
            }
            @Override public void onEnhancement(Usage usage, long elapsedMs, int in, int out) { }
        });
        assertEquals("hi", client.transcribe(new byte[3200], config(Provider.GROQ, "whisper-large-v3"), "en", false).text);
    }

    private ProviderConfig config(Provider provider, String model) {
        String endpoint = provider == Provider.OPENROUTER || model.startsWith("gpt-4o-mini") && !model.contains("transcribe")
                ? provider.enhancementEndpoint() : provider.transcriptionEndpoint();
        return new ProviderConfig(provider, endpoint, model, "fixture-key", false);
    }

    private CloudClient client() {
        return client(chain -> chain.proceed(chain.request()));
    }

    private CloudClient client(okhttp3.Interceptor after) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder().addInterceptor(chain -> {
            Request request = chain.request();
            HttpUrl destination = server.url(request.url().encodedPath());
            return chain.proceed(request.newBuilder().url(destination).build());
        }).addInterceptor(after);
        CloudClient client = new CloudClient(builder);
        client.setUsageListener(new Usage.Listener() {
            @Override public void onTranscription(Usage usage, long elapsedMs, double seconds) {
                assertTrue(elapsedMs >= 0);
                transcriptions.add(usage);
                audioSeconds.add(seconds);
            }
            @Override public void onEnhancement(Usage usage, long elapsedMs, int in, int out) {
                enhancements.add(usage);
                characters.add(new int[]{in, out});
            }
        });
        return client;
    }
}
