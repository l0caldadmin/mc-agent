package adris.altoclef.player2api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Coordinates optional multi-agent tasks emitted by the LLM.
 */
public class AgentTaskCoordinator {

    public static final class AgentTask {
        private final String agentId;
        private final String command;
        private final String goal;

        public AgentTask(String agentId, String command, String goal) {
            this.agentId = agentId;
            this.command = command;
            this.goal = goal;
        }

        public String getAgentId() {
            return agentId;
        }

        public String getCommand() {
            return command;
        }

        public String getGoal() {
            return goal;
        }
    }

    private final Queue<AgentTask> pendingTasks = new ConcurrentLinkedQueue<>();
    private final Map<String, Deque<String>> embeddedMemory = new ConcurrentHashMap<>();

    public int enqueueTasksFromResponse(JsonObject response) {
        if (!ChatclefConfigPersistantState.isMultiAgentEnabled()) {
            return 0;
        }
        if (response == null || !response.has("agent_tasks") || !response.get("agent_tasks").isJsonArray()) {
            return 0;
        }

        JsonArray arr = response.getAsJsonArray("agent_tasks");
        int maxToAdd = ChatclefConfigPersistantState.getMaxAgentTasksPerTurn();
        int added = 0;
        for (JsonElement element : arr) {
            if (added >= maxToAdd) {
                break;
            }
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject obj = element.getAsJsonObject();
            String agentId = normalized(Utils.getStringJsonSafely(obj, "agent"), "general");
            String command = normalized(Utils.getStringJsonSafely(obj, "command"), "");
            String goal = normalized(Utils.getStringJsonSafely(obj, "goal"), "");
            if (command.isEmpty()) {
                continue;
            }
            pendingTasks.offer(new AgentTask(agentId, command, goal));
            addMemory(agentId, "Queued task: " + command + (goal.isEmpty() ? "" : " | Goal: " + goal));
            added++;
        }
        return added;
    }

    public AgentTask pollNextTask() {
        return pendingTasks.poll();
    }

    public boolean hasPendingTasks() {
        return !pendingTasks.isEmpty();
    }

    public void addResult(String agentId, String result) {
        addMemory(normalized(agentId, "general"), normalized(result, ""));
    }

    public String dumpEmbeddedMemory() {
        if (embeddedMemory.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, Deque<String>> entry : embeddedMemory.entrySet()) {
            builder.append("[").append(entry.getKey()).append("]\n");
            for (String line : entry.getValue()) {
                builder.append("- ").append(line).append("\n");
            }
        }
        return builder.toString().trim();
    }

    public void clear() {
        pendingTasks.clear();
        embeddedMemory.clear();
    }

    private void addMemory(String agentId, String note) {
        if (note.isEmpty()) {
            return;
        }
        int maxEntries = ChatclefConfigPersistantState.getMaxAgentMemoryEntries();
        Deque<String> queue = embeddedMemory.computeIfAbsent(agentId, ignored -> new ArrayDeque<>());
        queue.addLast(note);
        while (queue.size() > maxEntries) {
            queue.removeFirst();
        }
    }

    private static String normalized(String text, String fallback) {
        if (text == null) {
            return fallback;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }
}
