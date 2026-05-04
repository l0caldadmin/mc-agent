package adris.altoclef.diagnostics;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.CommandException;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.ChickenEntity;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

public class BaritoneDiagnosticRunner {

    private static final long MINUTE_MS = 60_000L;
    private static final long SECOND_MS = 1_000L;
    private static final long DEFAULT_STEP_TIMEOUT_MS = 120_000L;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AltoClef mod;
    private final Path reportPath;
    private final List<DiagnosticStep> steps = new ArrayList<>();
    private final List<String> reportLines = new ArrayList<>();

    private boolean running;
    private int round;
    private int stepIndex;

    private long startedAtMs;
    private long endsAtMs;
    private long nextStepAtMs;
    private long stepPauseMs;

    private DiagnosticStep inFlight;
    private long inFlightStartedAtMs;

    private int passCount;
    private int failCount;
    private int skipCount;

    public BaritoneDiagnosticRunner(AltoClef mod) {
        this.mod = mod;
        this.reportPath = FabricLoader.getInstance().getConfigDir().resolve("baritone_diagnostic_report.txt");
        initializeSteps();
    }

    public synchronized void tick() {
        if (!running) {
            return;
        }

        long now = System.currentTimeMillis();

        if (inFlight != null) {
            if (now - inFlightStartedAtMs > inFlight.timeoutMs) {
                markFail(inFlight, "Timed out after " + (inFlight.timeoutMs / 1000) + "s");
                inFlight = null;
                scheduleNext(now);
            }
            return;
        }

        if (now >= endsAtMs) {
            finish("Duration elapsed.");
            return;
        }

        if (now < nextStepAtMs) {
            return;
        }

        DiagnosticStep next = steps.get(stepIndex);
        stepIndex++;
        if (stepIndex >= steps.size()) {
            stepIndex = 0;
            round++;
        }

        PreconditionResult precondition = next.precondition.apply(mod);
        if (!precondition.runnable) {
            markSkip(next, precondition.reason);
            scheduleNext(now);
            return;
        }

        String command = next.commandBuilder.build(round, mod);
        if (command == null || command.isBlank()) {
            markSkip(next, "Command builder returned empty command");
            scheduleNext(now);
            return;
        }

        String toRun = command;
        if (!toRun.startsWith(mod.getModSettings().getCommandPrefix())) {
            toRun = mod.getModSettings().getCommandPrefix() + toRun;
        }

        inFlight = next;
        inFlightStartedAtMs = now;
        logAndReport("RUN", next.name, "Executing command: " + toRun);

        try {
            AltoClef.getCommandExecutor().execute(
                    toRun,
                    () -> {
                        synchronized (BaritoneDiagnosticRunner.this) {
                            if (inFlight == null) {
                                return;
                            }
                            long durationMs = System.currentTimeMillis() - inFlightStartedAtMs;
                            markPass(inFlight, "Completed in " + durationMs + "ms");
                            inFlight = null;
                            scheduleNext(System.currentTimeMillis());
                        }
                    },
                    (CommandException err) -> {
                        synchronized (BaritoneDiagnosticRunner.this) {
                            if (inFlight == null) {
                                return;
                            }
                            String message = err == null ? "Unknown command error" : err.getMessage();
                            markFail(inFlight, message);
                            inFlight = null;
                            scheduleNext(System.currentTimeMillis());
                        }
                    }
            );
        } catch (Exception e) {
            markFail(next, "Execution threw: " + e.getMessage());
            inFlight = null;
            scheduleNext(System.currentTimeMillis());
        }
    }

    public synchronized void start(int durationMinutes, int stepPauseSeconds) {
        if (durationMinutes < 1) {
            durationMinutes = 1;
        }
        if (stepPauseSeconds < 5) {
            stepPauseSeconds = 5;
        }

        resetState();

        this.running = true;
        this.round = 1;
        this.stepIndex = 0;
        this.startedAtMs = System.currentTimeMillis();
        this.endsAtMs = startedAtMs + (durationMinutes * MINUTE_MS);
        this.stepPauseMs = stepPauseSeconds * SECOND_MS;
        this.nextStepAtMs = startedAtMs;

        reportLines.add("Baritone Diagnostic Report");
        reportLines.add("Started: " + timestampNow());
        reportLines.add("Duration minutes: " + durationMinutes);
        reportLines.add("Step pause seconds: " + stepPauseSeconds);
        reportLines.add("Steps per round: " + steps.size());
        reportLines.add("-");

        mod.log("[Diag] Baritone diagnostics started for " + durationMinutes + "m. Report: " + reportPath);
    }

    public synchronized void stop(String reason) {
        if (!running) {
            mod.log("[Diag] Not running.");
            return;
        }
        finish(reason == null ? "Stopped by user." : reason);
    }

    public synchronized String statusLine() {
        if (!running) {
            return "Baritone diagnostics: idle. Last report: " + reportPath;
        }
        long now = System.currentTimeMillis();
        long remainingSec = Math.max(0, (endsAtMs - now) / 1000);
        String inFlightName = inFlight == null ? "none" : inFlight.name;
        return "Baritone diagnostics: running | round=" + round + " step=" + (stepIndex + 1) + "/" + steps.size()
                + " | inFlight=" + inFlightName + " | remaining=" + remainingSec + "s"
                + " | pass=" + passCount + " fail=" + failCount + " skip=" + skipCount;
    }

    public synchronized Path getReportPath() {
        return reportPath;
    }

    private void finish(String reason) {
        running = false;
        long totalSec = (System.currentTimeMillis() - startedAtMs) / 1000;

        reportLines.add("-");
        reportLines.add("Finished: " + timestampNow());
        reportLines.add("Reason: " + reason);
        reportLines.add("Runtime seconds: " + totalSec);
        reportLines.add("PASS=" + passCount + " FAIL=" + failCount + " SKIP=" + skipCount);

        try {
            Files.createDirectories(reportPath.getParent());
            Files.writeString(reportPath, String.join(System.lineSeparator(), reportLines));
            mod.log("[Diag] Completed. PASS=" + passCount + " FAIL=" + failCount + " SKIP=" + skipCount);
            mod.log("[Diag] Report written to: " + reportPath);
        } catch (IOException e) {
            mod.log("[Diag] Failed writing report: " + e.getMessage());
        }
    }

    private void resetState() {
        running = false;
        round = 0;
        stepIndex = 0;
        startedAtMs = 0;
        endsAtMs = 0;
        nextStepAtMs = 0;
        inFlight = null;
        inFlightStartedAtMs = 0;

        passCount = 0;
        failCount = 0;
        skipCount = 0;
        reportLines.clear();
    }

    private void scheduleNext(long now) {
        nextStepAtMs = now + stepPauseMs;
    }

    private void markPass(DiagnosticStep step, String details) {
        passCount++;
        logAndReport("PASS", step.name, details);
    }

    private void markFail(DiagnosticStep step, String details) {
        failCount++;
        logAndReport("FAIL", step.name, details);
    }

    private void markSkip(DiagnosticStep step, String details) {
        skipCount++;
        logAndReport("SKIP", step.name, details);
    }

    private void logAndReport(String status, String stepName, String details) {
        String line = String.format(Locale.ROOT, "%s | %s | %s | %s",
                timestampNow(), status, stepName, details);
        reportLines.add(line);
        mod.log("[Diag] " + status + " " + stepName + " - " + details);
    }

    private String timestampNow() {
        return LocalDateTime.now().format(TS);
    }

    private void initializeSteps() {
        steps.add(new DiagnosticStep(
                "idle-command",
                (r, m) -> "idle",
                m -> PreconditionResult.ok(),
                25_000L
        ));

        steps.add(new DiagnosticStep(
                "goto-offset",
                (r, m) -> {
                    Vec3d p = m.getPlayer().getPos();
                    int direction = (r % 2 == 0) ? -1 : 1;
                    int x = (int) Math.floor(p.x) + (8 * direction);
                    int z = (int) Math.floor(p.z) + (6 * direction);
                    return "goto " + x + " " + z;
                },
                m -> PreconditionResult.require(m.inGame(), "Not in world"),
                DEFAULT_STEP_TIMEOUT_MS
        ));

        steps.add(new DiagnosticStep(
                "scan-block-dirt",
                (r, m) -> "scan dirt",
                m -> PreconditionResult.ok(),
                20_000L
        ));

        steps.add(new DiagnosticStep(
                "scan-entity-chicken",
                (r, m) -> "scan chicken",
                m -> PreconditionResult.ok(),
                20_000L
        ));

        steps.add(new DiagnosticStep(
                "get-dirt-1",
                (r, m) -> "get dirt 1",
                m -> PreconditionResult.require(m.inGame(), "Not in world"),
                180_000L
        ));

        steps.add(new DiagnosticStep(
                "attack-chicken-1",
                (r, m) -> "attack chicken 1",
                this::hasNearbyChicken,
                120_000L
        ));

        steps.add(new DiagnosticStep(
                "hero-hostile-defense",
                (r, m) -> "hero",
                m -> PreconditionResult.require(m.inGame(), "Not in world"),
                90_000L
        ));
    }

    private PreconditionResult hasNearbyChicken(AltoClef m) {
        if (!m.inGame()) {
            return PreconditionResult.require(false, "Not in world");
        }
        for (Entity entity : m.getEntityTracker().getCloseEntities()) {
            if (entity instanceof ChickenEntity) {
                return PreconditionResult.ok();
            }
        }
        return PreconditionResult.require(false, "No nearby chicken found");
    }

    private static class DiagnosticStep {
        private final String name;
        private final StepCommandBuilder commandBuilder;
        private final Function<AltoClef, PreconditionResult> precondition;
        private final long timeoutMs;

        private DiagnosticStep(String name,
                               StepCommandBuilder commandBuilder,
                               Function<AltoClef, PreconditionResult> precondition,
                               long timeoutMs) {
            this.name = name;
            this.commandBuilder = commandBuilder;
            this.precondition = precondition;
            this.timeoutMs = timeoutMs;
        }
    }

    private interface StepCommandBuilder {
        String build(int round, AltoClef mod);
    }

    private static class PreconditionResult {
        private final boolean runnable;
        private final String reason;

        private PreconditionResult(boolean runnable, String reason) {
            this.runnable = runnable;
            this.reason = reason;
        }

        public static PreconditionResult ok() {
            return new PreconditionResult(true, "");
        }

        public static PreconditionResult require(boolean condition, String reasonIfFalse) {
            if (condition) {
                return ok();
            }
            return new PreconditionResult(false, reasonIfFalse);
        }
    }
}
