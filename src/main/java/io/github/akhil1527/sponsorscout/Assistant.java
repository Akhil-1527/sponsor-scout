package io.github.akhil1527.sponsorscout;

import com.google.genai.Client;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Backend for the voice page, standing in for Alexa+. Gemini picks and chains the tools, and it reaches them over
 * MCP at this app's own /mcp endpoint, the same way Alexa+ or any other MCP client would.
 */
@RestController
class Assistant {

    record ScreenJob(int n, String jobId, String company, String title) {
    }

    record Ask(String text, String conversation, List<ScreenJob> screen) {
    }

    record Step(String tool, JsonNode result) {
    }

    record Answer(String speech, List<Step> steps) {
    }

    record Status(boolean assistant, String data, int jobs) {
    }

    private static final Log log = LogFactory.getLog(Assistant.class);

    private static final String RULES = """
            You are Sponsor Scout, a voice assistant for people on F-1 OPT or H-1B who want US jobs at employers \
            that actually file H-1B paperwork. Your replies are spoken aloud.
            - Answer in one to three short sentences, under 60 words. No markdown, lists, links or job IDs.
            - Write numbers as digits, like 57 or $179,000. The text is also shown on screen.
            - Get every fact from the tools. Never invent jobs, employers or numbers.
            - Every tool result has a "speech" field. Build your answer from it.
            - The screen shows the details as cards, so don't read out long lists.
            - "The second one" or a company name means a job on screen, listed below. Use its jobId.
            - A job the user names that isn't on screen is probably in their tracker. Call list_applications to \
            find its jobId.
            - Chain tools when the user asks for several things, for example find openings and then save one.
            - If you need something from the user, ask one short question.
            """;

    private final ChatClient chat;
    private final Environment env;
    private final JsonMapper json;
    private final Sponsors sponsors;
    private final JobBoards boards;
    private List<ToolCallback> tools;

    Assistant(@Value("${GEMINI_API_KEY:}") String key, @Value("${SCOUT_MODEL:gemini-3.5-flash-lite}") String model,
            Environment env, JsonMapper json, Sponsors sponsors, JobBoards boards) {
        this.env = env;
        this.json = json;
        this.sponsors = sponsors;
        this.boards = boards;
        if (key.isBlank()) {
            chat = null;
            return;
        }
        GoogleGenAiChatOptions.Builder options = GoogleGenAiChatOptions.builder();
        options.model(model);
        options.temperature(0.3);
        chat = ChatClient.builder(GoogleGenAiChatModel.builder()
                        .genAiClient(Client.builder().apiKey(key).build())
                        .options(options.build())
                        .build())
                // conversations stay in memory until restart; fine for a demo, evict before real traffic
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(
                        MessageWindowChatMemory.builder().maxMessages(20).build()).build())
                .build();
    }

    @PostMapping("/api/ask")
    Answer ask(@RequestBody Ask ask) {
        if (chat == null) {
            return new Answer("The voice assistant needs a Gemini API key. Put GEMINI_API_KEY in the .env file and "
                    + "restart.", List.of());
        }
        if (ask.text() == null || ask.text().isBlank() || ask.text().length() > 500) {
            return new Answer("Sorry, I didn't catch that.", List.of());
        }
        String conversation = ask.conversation() == null || ask.conversation().length() > 64 ? "default"
                : ask.conversation();
        List<Step> steps = new ArrayList<>();
        try {
            String speech = chat.prompt()
                    .system(RULES + context(ask.screen()))
                    .user(ask.text())
                    .toolCallbacks(tools().stream().<ToolCallback>map(t -> new Recorded(t, steps, json)).toList())
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversation))
                    .call()
                    .content();
            return new Answer(speech, steps);
        } catch (RuntimeException e) {
            log.warn("ask failed", e);
            return new Answer("Sorry, I couldn't reach the language model just now. Try again in a minute.", steps);
        }
    }

    @GetMapping("/api/status")
    Status status() {
        return new Status(chat != null, sponsors.coverage(), boards.all().size());
    }

    private static String context(List<ScreenJob> screen) {
        StringBuilder s = new StringBuilder("Today is " + LocalDate.now() + ".\n");
        if (screen != null && !screen.isEmpty()) {
            s.append("Jobs on screen:\n");
            screen.stream().limit(15).forEach(j -> s.append(j.n()).append(". ").append(j.title()).append(" at ")
                    .append(j.company()).append(", jobId ").append(j.jobId()).append('\n'));
        }
        return s.toString();
    }

    /** Connects on first use, because the MCP endpoint isn't listening until the app has started. */
    private synchronized List<ToolCallback> tools() {
        if (tools == null) {
            McpSyncClient client = McpClient.sync(HttpClientStreamableHttpTransport
                            .builder("http://localhost:" + env.getProperty("local.server.port")).build())
                    .requestTimeout(Duration.ofSeconds(60))
                    .build();
            client.initialize();
            tools = SyncMcpToolCallbackProvider.syncToolCallbacks(List.of(client));
        }
        return tools;
    }

    /** Passes each call through to the MCP tool and keeps the result so the page can draw cards. */
    private record Recorded(ToolCallback tool, List<Step> steps, JsonMapper json) implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return tool.getToolDefinition();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return tool.getToolMetadata();
        }

        @Override
        public String call(String input) {
            log.info(getToolDefinition().name() + " " + input);
            String out = tool.call(input);
            try {
                // MCP returns content blocks; our tools return one text block holding the result as JSON
                steps.add(new Step(getToolDefinition().name(),
                        json.readTree(json.readTree(out).path(0).path("text").asString())));
            } catch (RuntimeException e) {
                log.debug("no card for " + getToolDefinition().name(), e);
            }
            return out;
        }
    }
}
