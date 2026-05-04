package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.Arg;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.CommandException;
import adris.altoclef.diagnostics.BaritoneDiagnosticRunner;

public class BaritoneDiagnosticCommand extends Command {

    public BaritoneDiagnosticCommand() throws CommandException {
        super(
                "diagbaritone",
                "Run long-form Baritone diagnostics. Usage: diagbaritone start [minutes] [stepPauseSeconds] | diagbaritone status | diagbaritone stop | diagbaritone report",
                new Arg<>(String.class, "action", "status", 0),
                new Arg<>(String.class, "arg1", "", 1),
                new Arg<>(String.class, "arg2", "", 2)
        );
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String[] units = parser.getArgUnits();
        String action = units.length > 0 ? units[0].toLowerCase() : "status";

        BaritoneDiagnosticRunner runner = mod.getBaritoneDiagnosticRunner();

        switch (action) {
            case "start" -> {
                int minutes = units.length > 1 ? parsePositiveInt(units[1], "minutes") : 60;
                int pauseSeconds = units.length > 2 ? parsePositiveInt(units[2], "stepPauseSeconds") : 45;
                runner.start(minutes, pauseSeconds);
                log("Baritone diagnostics started. Duration=" + minutes + "m, stepPause=" + pauseSeconds + "s");
                log("Check status with: diagbaritone status");
            }
            case "stop" -> {
                runner.stop("Stopped by user command.");
            }
            case "status" -> {
                log(runner.statusLine());
            }
            case "report" -> {
                log("Baritone diagnostic report path: " + runner.getReportPath());
            }
            default -> throw new CommandException("Unknown action: " + action + ". Use start/status/stop/report");
        }

        finish();
    }

    private int parsePositiveInt(String value, String label) throws CommandException {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                throw new CommandException(label + " must be > 0, got: " + value);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new CommandException("Invalid number for " + label + ": " + value);
        }
    }
}
