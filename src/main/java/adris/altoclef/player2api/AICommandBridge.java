package adris.altoclef.player2api;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.CommandExecutor;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.ChatMessageEvent;
import adris.altoclef.player2api.status.AgentStatus;
import adris.altoclef.player2api.status.StatusUtils;
import adris.altoclef.player2api.status.WorldStatus;
import com.google.gson.JsonObject;
import net.minecraft.network.message.MessageType;

import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AICommandBridge {
    private static final Character DEFAULT_CHARACTER = new Character(
            "AI Agent",
            "AI",
            "Greet the user warmly and ask what they want to do in Minecraft.",
            "You are a helpful Minecraft copilot that is concise, practical and friendly.",
            new String[0]
    );

    private ConversationHistory conversationHistory;
    private Character character;
    public static boolean avoidNextMessageFlag = false;

    public static String initialPrompt = """
                        Role:
                        You are an AI Minecraft copilot that should prefer real in-game actions over discussion whenever the user asks for something actionable.

                        Personality:
                        Your character name is {{characterName}}.
                        {{characterDescription}}

                        Runtime Input:
                        The latest user message is wrapped as JSON:
                        {
                            "userMessage": "...",
                            "worldStatus": "...",
                            "agentStatus": "...",
                            "gameDebugMessages": "...",
                            "multiAgentMemory": "..."
                        }

                        Command Policy:
                        - If the user asks the bot to do something in Minecraft and a command exists, use a command.
                        - Prefer acting over explaining for requests like gather, craft, equip, move, follow, attack, scan, deposit, stop, or idle.
                        - Use `message` for brief acknowledgement, status, clarification, or plain conversation.
                        - Leave `command` empty only when no action is needed, information is missing, or no valid command fits.
                        - Never invent commands, arguments, items, structures, or syntax that are not in the command catalog.
                        - Do not use user-only commands unless the user is explicitly managing the mod itself.
                        - Do not emit multiple primary commands. Use one best next action.
                        - Prefer concrete commands over high-level promises.

                        Command Formatting:
                        - Put exactly one command string in `command`.
                        - Use command names from the catalog below.
                        - Do not wrap the command in JSON, markdown, backticks, or explanation text.
                        - Use arguments exactly as expected by the command examples.
                        - The system will add the command prefix automatically, so `get iron_pickaxe 1` is correct.

                        Clarification Policy:
                        - Ask a short question instead of guessing when a required target is missing.
                        - Examples: missing player name for `follow`, missing coordinates for `goto`, ambiguous item choice, or impossible request.
                        - If world or agent status already answers the ambiguity, do not ask.

                        Behavior Examples:
                        - If user says "kill sheep", prefer:
                            {"reason":"Direct action requested and attack command exists.","command":"attack sheep 1","message":"On it.","agent_tasks":[]}
                        - If user says "go kill mobs", prefer:
                            {"reason":"User wants nearby hostile mobs cleared.","command":"hero","message":"Clearing nearby hostiles.","agent_tasks":[]}
                        - If user says "build me a hut", do not invent place/build commands. Prefer:
                            {"reason":"No direct building command exists.","command":"","message":"I can gather materials, but I don't have a direct building command. Tell me what to collect or where to go.","agent_tasks":[]}
                        - If user asks a factual question like "what do I have?", prefer no command and answer briefly from status.
                        - Do not narrate a multi-step plan when one command is enough.

                        Multi-Agent Policy:
                        - `agent_tasks` is optional and only for useful follow-up work after the primary command.
                        - Each agent task should be a small, concrete command with a short goal.
                        - Do not duplicate the primary command inside `agent_tasks`.

                        Required Response JSON:
                        {
                            "reason": "Short internal reasoning about the next best action.",
                            "command": "Single command to run, or \"\".",
                            "message": "Short natural reply to the user, or \"\".",
                            "agent_tasks": [
                                {"agent": "builder", "goal": "optional goal", "command": "optional secondary command"}
                            ]
                        }

                        Hard Rules:
                        - Always output strict JSON only.
                        - Keep `reason` short and factual.
                        - Keep `message` concise, practical, and usually under 160 characters.
                        - Do not output markdown, code fences, tool docs, or API calls.
                        - If the user asks for a mod/config action, respond with a message and only use a user-only command if appropriate.

                        Command Playbook:
                        {{toolingPaths}}

                        Command Catalog:
                        {{validCommands}}
            """;

    private final CommandExecutor cmdExecutor;
    private final AltoClef mod;
    private final AgentTaskCoordinator agentTaskCoordinator = new AgentTaskCoordinator();

    private boolean enabled = true;
    private boolean playermode = false;

    private String lastQueuedMessage;
    private boolean llmProcessing = false;
    private boolean eventPolling = false;

    private final MessageBuffer altoClefMsgBuffer = new MessageBuffer(10);

    public static final ExecutorService llmThread = Executors.newSingleThreadExecutor();

    private final Queue<String> messageQueue = new ConcurrentLinkedQueue<>();

    public AICommandBridge(CommandExecutor cmdExecutor, AltoClef mod) {
        this.mod = mod;
        this.cmdExecutor = cmdExecutor;
        this.character = DEFAULT_CHARACTER;

        EventBus.subscribe(ChatMessageEvent.class, evt -> {
            if (!getPlayerMode()) {
                return;
            }
            String message = evt.messageContent();
            String sender = evt.senderName();
            float distance = StatusUtils.getUserNameDistance(mod, sender);
            if (distance > 200) {
                return;
            }
            MessageType messageType = evt.messageType();
            String receiver = mod.getPlayer().getName().getString();
            System.out.printf("[AIBridge/ChatMessageEvent] (%s) from (%s) type (%s), distance(%.2f)%n",
                    message, sender, messageType, distance);
            if (sender != null && !Objects.equals(sender, receiver)) {
                String wholeMessage = "Other players: [" + sender + "] " + message;
                addMessageToQueue(wholeMessage + "| Remember to roleplay as " + this.character.name);
            }
        });
    }

    private void updateInfo() {
        String validCommandsFormatted = buildValidCommands();
        String toolingPaths = buildToolingPaths();

        String newPrompt = Utils.replacePlaceholders(initialPrompt,
                Map.of(
                        "characterDescription", character.description,
                        "characterName", character.name,
                        "validCommands", validCommandsFormatted,
                        "toolingPaths", toolingPaths
                ));

        if (this.conversationHistory == null) {
            this.conversationHistory = new ConversationHistory(newPrompt, this.character.name, this.character.shortName);
        } else {
            this.conversationHistory.setBaseSystemPrompt(newPrompt);
        }
    }

    private String buildValidCommands() {
        StringBuilder commandListBuilder = new StringBuilder();
        for (Command c : AltoClef.getCommandExecutor().allCommands()) {
            commandListBuilder
                    .append("- ")
                    .append(c.getName())
                    .append(": ")
                    .append(c.getDescription())
                    .append("\n");
        }
        return commandListBuilder.toString().trim();
    }

    private String buildToolingPaths() {
        String prefix = cmdExecutor.getCommandPrefix();
        StringBuilder paths = new StringBuilder();
        paths.append("- Action requests should usually produce a command. Example syntax: ")
                .append(prefix)
                .append("get iron_pickaxe 1, ")
                .append(prefix)
                .append("goto 100 64 200, ")
                .append(prefix)
                .append("follow Steve.\n");
        paths.append("- Conversation-only requests should usually leave `command` empty and answer in `message`.\n");
        paths.append("- User-only mod control commands: ")
                .append(buildUserOnlyCommands(prefix))
                .append(".\n");
        paths.append("- Never use unknown commands. Choose the single best command from the catalog.");
        return paths.toString();
    }

    private String buildUserOnlyCommands(String prefix) {
        StringBuilder userOnly = new StringBuilder();
        for (Command c : AltoClef.getCommandExecutor().allCommands()) {
            String description = c.getDescription();
            if (description != null && description.toLowerCase().contains("only be run by the user")) {
                if (!userOnly.isEmpty()) {
                    userOnly.append(", ");
                }
                userOnly.append(prefix).append(c.getName());
            }
        }
        if (userOnly.isEmpty()) {
            return "none";
        }
        return userOnly.toString();
    }

    public void addAltoclefLogMessage(String message) {
        altoClefMsgBuffer.addMsg(message);
    }

    public void addMessageToQueue(String message) {
        if (message == null) {
            return;
        }
        if (message.equals(lastQueuedMessage)) {
            return;
        }

        messageQueue.offer(message);
        lastQueuedMessage = message;

        if (messageQueue.size() > 10) {
            messageQueue.poll();
        }
    }

    public void processChatWithAPI() {
        llmThread.submit(() -> {
            try {
                llmProcessing = true;
                String agentStatus = AgentStatus.fromMod(mod).toString();
                String worldStatus = WorldStatus.fromMod(mod).toString();
                String altoClefDebugMsgs = altoClefMsgBuffer.dumpAndGetString();
                String embeddedMemory = agentTaskCoordinator.dumpEmbeddedMemory();

                ConversationHistory historyWithStatus = conversationHistory.copyThenWrapLatestWithStatus(
                        worldStatus,
                        agentStatus,
                        altoClefDebugMsgs,
                        embeddedMemory
                );

                JsonObject response = Player2APIService.completeConversation(historyWithStatus);
                String responseAsString = response.toString();
                conversationHistory.addAssistantMessage(responseAsString);

                String llmMessage = Utils.getStringJsonSafely(response, "message");
                if (llmMessage != null && !llmMessage.isEmpty()) {
                    mod.logCharacterMessage(llmMessage, character, getPlayerMode());
                }

                agentTaskCoordinator.enqueueTasksFromResponse(response);

                String commandResponse = Utils.getStringJsonSafely(response, "command");
                if (commandResponse != null && !commandResponse.isEmpty()) {
                    executeCommand("general", commandResponse, commandResponse);
                } else {
                    executeNextAgentTask();
                }

            } catch (Exception e) {
                e.printStackTrace();
                System.err.println("Error communicating with provider API");
            } finally {
                llmProcessing = false;
                eventPolling = false;
            }
        });
    }

    private void executeNextAgentTask() {
        AgentTaskCoordinator.AgentTask next = agentTaskCoordinator.pollNextTask();
        if (next == null) {
            return;
        }
        executeCommand(next.getAgentId(), next.getCommand(), next.getGoal());
    }

    private void executeCommand(String agentId, String commandResponse, String goal) {
        String commandWithPrefix = cmdExecutor.isClientCommand(commandResponse)
                ? commandResponse
                : cmdExecutor.getCommandPrefix() + commandResponse;

        mod.isStopping = "@stop".equals(commandWithPrefix);
        cmdExecutor.execute(commandWithPrefix, () -> {
            if (!mod.isStopping) {
                if (!messageQueue.isEmpty()) {
                    return;
                }
                String goalSegment = (goal == null || goal.isBlank()) ? "" : (" | Goal: " + goal);
                String feedback = "Command feedback [" + agentId + "]: " + commandResponse + " finished."
                        + goalSegment
                        + " If no further action is needed, generate empty command \"\"."
                        + "| Remember to roleplay as " + this.character.name;
                addMessageToQueue(feedback);
                agentTaskCoordinator.addResult(agentId, "Finished: " + commandResponse + goalSegment);
            }
            executeNextAgentTask();
        }, (err) -> {
            String error = err == null ? "Unknown error" : err.getMessage();
            String feedback = "Command feedback [" + agentId + "]: " + commandResponse + " FAILED. Error: " + error
                    + "| Remember to roleplay as " + this.character.name;
            addMessageToQueue(feedback);
            agentTaskCoordinator.addResult(agentId, "Failed: " + commandResponse + " | Error: " + error);
            executeNextAgentTask();
        });
    }

    public void sendGreeting() {
        llmThread.submit(() -> {
            updateInfo();
            if (conversationHistory.isLoadedFromFile()) {
                addMessageToQueue(
                        "Welcome the user back. First response should not run a command."
                                + "| Remember to roleplay as " + this.character.name);
            } else {
                addMessageToQueue(
                        character.greetingInfo + " First response should not run a command."
                                + "| Remember to roleplay as " + this.character.name);
            }
        });
    }

    public void onTick() {
        if (conversationHistory == null) {
            updateInfo();
        }
        if (messageQueue.isEmpty()) {
            return;
        }
        if (!eventPolling && !llmProcessing) {
            eventPolling = true;
            String message = messageQueue.poll();
            conversationHistory.addUserMessage(message);
            if (messageQueue.isEmpty()) {
                processChatWithAPI();
            } else {
                eventPolling = false;
            }
        }
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean getEnabled() {
        return enabled;
    }

    public Character getCharacter() {
        return character;
    }

    public ConversationHistory conversationHistory() {
        return conversationHistory;
    }

    public void clearMemory() {
        if (conversationHistory != null) {
            conversationHistory.clear();
        }
        agentTaskCoordinator.clear();
    }

    public void reloadProviderConfig() {
        updateInfo();
    }

    public void setPlayerMode(boolean playermode) {
        this.playermode = playermode;
    }

    public boolean getPlayerMode() {
        return playermode;
    }
}
