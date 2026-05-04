package adris.altoclef.player2api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ChatclefConfigPersistantState {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("chatclef_config.json");
    private static ChatclefConfigPersistantState config = load();

    private String llmProvider = "ollama";
    private String llmBaseUrl = "http://127.0.0.1:11434";
    private String llmModel = "llama3.1";
    private String llmApiKey = "";
    private boolean multiAgentEnabled = true;
    private int maxAgentTasksPerTurn = 3;
    private int maxAgentMemoryEntries = 12;

    public static String getLlmProvider() {
        String provider = instance().llmProvider;
        return provider == null || provider.isBlank() ? "ollama" : provider.trim().toLowerCase();
    }

    public static String getLlmBaseUrl() {
        String url = instance().llmBaseUrl;
        return url == null ? "" : url.trim();
    }

    public static String getLlmModel() {
        String model = instance().llmModel;
        return model == null ? "" : model.trim();
    }

    public static String getLlmApiKey() {
        String key = instance().llmApiKey;
        return key == null ? "" : key.trim();
    }

    public static boolean isMultiAgentEnabled() {
        return instance().multiAgentEnabled;
    }

    public static int getMaxAgentTasksPerTurn() {
        int configured = instance().maxAgentTasksPerTurn;
        return Math.max(1, configured);
    }

    public static int getMaxAgentMemoryEntries() {
        int configured = instance().maxAgentMemoryEntries;
        return Math.max(4, configured);
    }

    public static void setLlmProvider(String provider) {
        instance().llmProvider = provider == null ? "ollama" : provider.trim().toLowerCase();
        save();
    }

    public static void setLlmBaseUrl(String baseUrl) {
        instance().llmBaseUrl = baseUrl == null ? "" : baseUrl.trim();
        save();
    }

    public static void setLlmModel(String model) {
        instance().llmModel = model == null ? "" : model.trim();
        save();
    }

    public static void setLlmApiKey(String apiKey) {
        instance().llmApiKey = apiKey == null ? "" : apiKey.trim();
        save();
    }

    public static void setMultiAgentEnabled(boolean enabled) {
        instance().multiAgentEnabled = enabled;
        save();
    }

    public static void setMaxAgentTasksPerTurn(int maxTasks) {
        instance().maxAgentTasksPerTurn = Math.max(1, maxTasks);
        save();
    }

    public static void setMaxAgentMemoryEntries(int maxEntries) {
        instance().maxAgentMemoryEntries = Math.max(4, maxEntries);
        save();
    }

    private static ChatclefConfigPersistantState load() {
        if (Files.exists(CONFIG_PATH)) {
            try {
                String json = Files.readString(CONFIG_PATH);
                System.out.println("[ChatclefConfigPersistantState]: Reading from file...");
                return GSON.fromJson(json, ChatclefConfigPersistantState.class);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        System.out.println("[ChatclefConfigPersistantState]: Could not load file, writing defaults.");
        ChatclefConfigPersistantState defaults = new ChatclefConfigPersistantState();
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(defaults));
        } catch (IOException e) {
            e.printStackTrace();
        }
        return defaults;
    }

    private static void save() {
        System.out.println("[ChatclefConfigPersistantState]: save() called");
        try {
            Files.writeString(CONFIG_PATH, GSON.toJson(config));
            System.out.println("[ChatclefConfigPersistantState]: Writing to file...");
        } catch (IOException e) {
            System.err.println("[ChatclefConfigPersistantState]: Writing to file FAILED");
            e.printStackTrace();
        }
    }


    private static ChatclefConfigPersistantState instance() {
        // if (config == null) {
        // config = load();
        // }
        return config;
    }
}
