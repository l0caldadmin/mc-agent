package adris.altoclef.player2api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class Player2APIService {

    private static final String DEFAULT_OPENAI_BASE = "https://api.openai.com";
    private static final String DEFAULT_OPENAI_MODEL = "gpt-4.1-mini";
    private static final String DEFAULT_OLLAMA_BASE = "http://127.0.0.1:11434";
    private static final String DEFAULT_OLLAMA_MODEL = "llama3.1";

    private static JsonObject sendPostJson(String baseUrl, String endpoint, JsonObject requestBody, String bearerToken)
            throws Exception {
        URL url = new URI(normalizeBaseUrl(baseUrl) + endpoint).toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "application/json; charset=utf-8");
        if (bearerToken != null && !bearerToken.isBlank()) {
            connection.setRequestProperty("Authorization", "Bearer " + bearerToken);
        }

        connection.setDoOutput(true);
        try (OutputStream os = connection.getOutputStream()) {
            byte[] input = requestBody.toString().getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int responseCode = connection.getResponseCode();
        if (responseCode < 200 || responseCode >= 300) {
            String errBody = "";
            try (BufferedReader errReader = new BufferedReader(
                    new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                StringBuilder builder = new StringBuilder();
                while ((line = errReader.readLine()) != null) {
                    builder.append(line);
                }
                errBody = builder.toString();
            } catch (Exception ignored) {
            }
            throw new IOException("HTTP " + responseCode + " on " + endpoint + ": " + errBody);
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            return JsonParser.parseString(response.toString()).getAsJsonObject();
        }
    }

    private static JsonObject buildJsonResponseFormat() {
        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_object");
        return responseFormat;
    }

    public static JsonObject completeConversation(ConversationHistory conversationHistory) throws Exception {
        String content = completeConversationToString(conversationHistory);
        return Utils.parseCleanedJson(content);
    }

    public static String completeConversationToString(ConversationHistory conversationHistory) throws Exception {
        String provider = ChatclefConfigPersistantState.getLlmProvider();
        if ("openai".equals(provider)) {
            return completeWithOpenAI(conversationHistory);
        }
        return completeWithOllama(conversationHistory);
    }

    public static String pingProvider() throws Exception {
        String provider = ChatclefConfigPersistantState.getLlmProvider();
        String baseUrl = ChatclefConfigPersistantState.getLlmBaseUrl();
        String model = ChatclefConfigPersistantState.getLlmModel();

        JsonArray messages = new JsonArray();
        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", "Reply with exactly: PONG");
        messages.add(userMessage);

        if ("openai".equals(provider)) {
            if (baseUrl.isBlank()) {
                baseUrl = DEFAULT_OPENAI_BASE;
            }
            if (model.isBlank()) {
                model = DEFAULT_OPENAI_MODEL;
            }

            String apiKey = ChatclefConfigPersistantState.getLlmApiKey();
            if (apiKey.isBlank()) {
                String envKey = System.getenv("OPENAI_API_KEY");
                if (envKey != null) {
                    apiKey = envKey;
                }
            }

            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", model);
            requestBody.add("messages", messages);
            requestBody.addProperty("temperature", 0);
            requestBody.add("response_format", buildJsonResponseFormat());

            JsonObject response = sendPostJson(baseUrl, "/v1/chat/completions", requestBody, apiKey);
            JsonArray choices = response.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                throw new Exception("Invalid OpenAI response: missing choices");
            }
            JsonObject messageObject = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            String content = messageObject == null ? "" : Utils.getStringJsonSafely(messageObject, "content");
            return content == null ? "" : content;
        }

        if (baseUrl.isBlank()) {
            baseUrl = DEFAULT_OLLAMA_BASE;
        }
        if (model.isBlank()) {
            model = DEFAULT_OLLAMA_MODEL;
        }

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", model);
        requestBody.add("messages", messages);
        requestBody.addProperty("stream", false);
        requestBody.addProperty("format", "json");

        JsonObject response = sendPostJson(baseUrl, "/api/chat", requestBody, "");
        JsonObject messageObject = response.getAsJsonObject("message");
        String content = messageObject == null ? "" : Utils.getStringJsonSafely(messageObject, "content");
        return content == null ? "" : content;
    }

    private static String completeWithOpenAI(ConversationHistory conversationHistory) throws Exception {
        String baseUrl = ChatclefConfigPersistantState.getLlmBaseUrl();
        if (baseUrl.isBlank()) {
            baseUrl = DEFAULT_OPENAI_BASE;
        }

        String model = ChatclefConfigPersistantState.getLlmModel();
        if (model.isBlank()) {
            String envModel = System.getenv("OPENAI_MODEL");
            model = (envModel == null || envModel.isBlank()) ? DEFAULT_OPENAI_MODEL : envModel;
        }

        String apiKey = ChatclefConfigPersistantState.getLlmApiKey();
        if (apiKey.isBlank()) {
            String envKey = System.getenv("OPENAI_API_KEY");
            if (envKey != null) {
                apiKey = envKey;
            }
        }

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", model);
        requestBody.add("messages", buildMessagesArray(conversationHistory));
        requestBody.addProperty("temperature", 0.1);
        requestBody.add("response_format", buildJsonResponseFormat());

        JsonObject response = sendPostJson(baseUrl, "/v1/chat/completions", requestBody, apiKey);
        JsonArray choices = response.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new Exception("Invalid OpenAI response: missing choices");
        }
        JsonObject messageObject = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        if (messageObject == null || !messageObject.has("content")) {
            throw new Exception("Invalid OpenAI response: missing message.content");
        }
        return messageObject.get("content").getAsString();
    }

    private static String completeWithOllama(ConversationHistory conversationHistory) throws Exception {
        String baseUrl = ChatclefConfigPersistantState.getLlmBaseUrl();
        if (baseUrl.isBlank()) {
            baseUrl = DEFAULT_OLLAMA_BASE;
        }

        String model = ChatclefConfigPersistantState.getLlmModel();
        if (model.isBlank()) {
            model = DEFAULT_OLLAMA_MODEL;
        }

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", model);
        requestBody.add("messages", buildMessagesArray(conversationHistory));
        requestBody.addProperty("stream", false);
        requestBody.addProperty("format", "json");

        JsonObject response = sendPostJson(baseUrl, "/api/chat", requestBody, "");
        JsonObject messageObject = response.getAsJsonObject("message");
        if (messageObject == null || !messageObject.has("content")) {
            throw new Exception("Invalid Ollama response: missing message.content");
        }
        return messageObject.get("content").getAsString();
    }

    private static JsonArray buildMessagesArray(ConversationHistory conversationHistory) {
        JsonArray messagesArray = new JsonArray();
        for (JsonObject msg : conversationHistory.getListJSON()) {
            JsonObject copy = new JsonObject();
            copy.addProperty("role", Utils.getStringJsonSafely(msg, "role"));
            copy.addProperty("content", Utils.getStringJsonSafely(msg, "content"));
            messagesArray.add(copy);
        }
        return messagesArray;
    }

    private static String normalizeBaseUrl(String input) {
        String trimmed = input == null ? "" : input.trim();
        if (trimmed.endsWith("/")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
