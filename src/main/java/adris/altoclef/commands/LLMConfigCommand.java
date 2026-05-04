package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.Arg;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.CommandException;
import adris.altoclef.player2api.ChatclefConfigPersistantState;
import adris.altoclef.player2api.Player2APIService;

public class LLMConfigCommand extends Command {

    public LLMConfigCommand() throws CommandException {
        super(
                "llm",
                "Show or update LLM provider settings. Examples: llm show, llm provider openai, llm model gpt-4.1-mini, llm baseurl http://127.0.0.1:11434, llm apikey clear, llm multiagent on",
                new Arg<>(String.class, "action", null, 0),
                new Arg<>(String.class, "key", null, 0),
                new Arg<>(String.class, "value", null, 0)
        );
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String[] units = parser.getArgUnits();

        if (units.length == 0 || equalsAny(units[0], "show", "status")) {
            showCurrentConfig(mod);
            finish();
            return;
        }

        String action = units[0].toLowerCase();
        switch (action) {
            case "ping" -> {
                String provider = ChatclefConfigPersistantState.getLlmProvider();
                String baseUrl = ChatclefConfigPersistantState.getLlmBaseUrl();
                String model = ChatclefConfigPersistantState.getLlmModel();
                log("Pinging " + provider + " at " + baseUrl + " with model " + model + "...");
                try {
                    String response = Player2APIService.pingProvider();
                    String oneLine = response == null ? "" : response.replace('\n', ' ').trim();
                    if (oneLine.length() > 120) {
                        oneLine = oneLine.substring(0, 120) + "...";
                    }
                    log("LLM ping OK. Response: " + oneLine);
                } catch (Exception e) {
                    logError("LLM ping failed: " + e.getMessage());
                }
            }
            case "provider" -> {
                requireArgCount(units, 2, "Usage: llm provider <openai|ollama>");
                String provider = units[1].toLowerCase();
                if (!equalsAny(provider, "openai", "ollama")) {
                    throw new CommandException("Provider must be one of: openai, ollama");
                }
                ChatclefConfigPersistantState.setLlmProvider(provider);
                mod.getAiBridge().reloadProviderConfig();
                log("LLM provider set to " + provider + ".");
            }
            case "model" -> {
                requireArgCount(units, 2, "Usage: llm model <modelName>");
                ChatclefConfigPersistantState.setLlmModel(units[1]);
                mod.getAiBridge().reloadProviderConfig();
                log("LLM model set to " + units[1] + ".");
            }
            case "baseurl" -> {
                requireArgCount(units, 2, "Usage: llm baseurl <url>");
                ChatclefConfigPersistantState.setLlmBaseUrl(units[1]);
                mod.getAiBridge().reloadProviderConfig();
                log("LLM base URL set to " + units[1] + ".");
            }
            case "apikey" -> {
                requireArgCount(units, 2, "Usage: llm apikey <key|clear>");
                String value = units[1];
                if (equalsAny(value, "clear", "none", "null")) {
                    ChatclefConfigPersistantState.setLlmApiKey("");
                    log("LLM API key cleared.");
                } else {
                    ChatclefConfigPersistantState.setLlmApiKey(value);
                    log("LLM API key updated.");
                }
            }
            case "multiagent" -> {
                requireArgCount(units, 2, "Usage: llm multiagent <on|off>");
                boolean enabled = parseOnOff(units[1]);
                ChatclefConfigPersistantState.setMultiAgentEnabled(enabled);
                mod.getAiBridge().reloadProviderConfig();
                log("Multi-agent mode set to " + (enabled ? "on" : "off") + ".");
            }
            default -> throw new CommandException("Unknown llm action: " + action + ". Try: llm show");
        }

        finish();
    }

    private void showCurrentConfig(AltoClef mod) {
        String provider = ChatclefConfigPersistantState.getLlmProvider();
        String model = ChatclefConfigPersistantState.getLlmModel();
        String baseUrl = ChatclefConfigPersistantState.getLlmBaseUrl();
        String apiKey = ChatclefConfigPersistantState.getLlmApiKey();
        boolean multiAgent = ChatclefConfigPersistantState.isMultiAgentEnabled();

        mod.log("LLM provider: " + provider);
        mod.log("LLM model: " + model);
        mod.log("LLM base URL: " + baseUrl);
        mod.log("LLM API key: " + (apiKey.isBlank() ? "(not set)" : "(set)"));
        mod.log("Multi-agent: " + (multiAgent ? "on" : "off"));
        mod.log("Usage: llm show | llm ping | llm provider <openai|ollama> | llm model <name> | llm baseurl <url> | llm apikey <key|clear> | llm multiagent <on|off>");
    }

    private static void requireArgCount(String[] args, int minCount, String usage) throws CommandException {
        if (args.length < minCount) {
            throw new CommandException(usage);
        }
    }

    private static boolean parseOnOff(String input) throws CommandException {
        String value = input.toLowerCase();
        if (equalsAny(value, "on", "true", "1", "yes")) {
            return true;
        }
        if (equalsAny(value, "off", "false", "0", "no")) {
            return false;
        }
        throw new CommandException("Expected on/off value but got: " + input);
    }

    private static boolean equalsAny(String source, String... options) {
        for (String option : options) {
            if (source.equalsIgnoreCase(option)) {
                return true;
            }
        }
        return false;
    }
}
