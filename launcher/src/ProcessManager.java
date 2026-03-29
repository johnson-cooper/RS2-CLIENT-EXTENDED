import javax.swing.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class ProcessManager {

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, Process> processes = new ConcurrentHashMap<>();
    private final Consumer<String> logger;

    public ProcessManager(Consumer<String> logger) {
        this.logger = logger;
    }

    public Process startProcess(String name, List<String> command, Path workingDir) throws Exception {
        if (isProcessAlive(name)) {
            logger.accept(name + " already running");
            return null;
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        if (workingDir != null) {
            pb.directory(workingDir.toFile());
        }
        pb.redirectErrorStream(true);

        Process process = pb.start();
        processes.put(name, process);

        streamLogs(name, process);
        watchExit(name, process);

        return process;
    }

    public boolean isProcessAlive(String name) {
        Process process = processes.get(name);
        if (process == null) return false;
        if (process.isAlive()) return true;
        processes.remove(name);
        return false;
    }

    public void streamLogs(String name, Process process) {
        executor.submit(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.accept("[" + name + "] " + line);
                }
            } catch (IOException e) {
                logger.accept("Log stream ended for " + name + ": " + e.getMessage());
            }
        });
    }

    public void watchExit(String name, Process process) {
        executor.submit(() -> {
            try {
                int code = process.waitFor();
                processes.remove(name);
                logger.accept(name + " exited with code " + code);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * Offers to stop all running processes, then shuts down the executor.
     * Call this from a window-close / shutdown-hook handler.
     */
    public void shutdownAll() {
        if (!processes.isEmpty()) {
            int choice = JOptionPane.showConfirmDialog(
                    null,
                    "Stop all running processes (Server, Client, Website) before exiting?",
                    "Processes still running",
                    JOptionPane.YES_NO_OPTION);

            if (choice == JOptionPane.YES_OPTION) {
                for (Map.Entry<String, Process> entry : processes.entrySet()) {
                    entry.getValue().destroyForcibly();
                    logger.accept("Stopped " + entry.getKey());
                }
            }
        }

        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                logger.accept("Executor did not terminate cleanly.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}