import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.DirectoryStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Launcher {

    private final Path rootDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    private final Path configFile = rootDir.resolve("config.properties");
    private static final int MAX_LOG_LINES = 2000;
    private Path getAgentJar() {
        Path clientJar = Paths.get(clientJarField.getText().trim());
        return clientJar.getParent().resolve("agent.jar");
    }

    private JFrame frame;
    private JTextField websiteExeField = new JTextField();
    private JTextField serverJarField = new JTextField();
    private JTextField clientJarField = new JTextField();
    private JTextArea logArea = new JTextArea();
    private JLabel statusLabel = new JLabel("Idle");

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, Process> processes = new ConcurrentHashMap<String, Process>();

    public Launcher() {
        setupUI();
        loadConfig();
        autoDetectPaths();
        refreshFields();
    }

    private void setupUI() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }

        frame = new JFrame("RS2 Progressive Launcher");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(860, 660);
        frame.setLocationRelativeTo(null);
        frame.setLayout(new BorderLayout());

        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBorder(new EmptyBorder(12, 12, 12, 12));
        root.setBackground(new Color(20, 20, 28));

        JLabel title = new JLabel("RS2 Progressive Launcher");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20f));
        title.setForeground(new Color(220, 230, 255));

        JLabel subtitle = new JLabel("Auto-detects website / server / client beside the launcher.");
        subtitle.setForeground(new Color(190, 190, 200));

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBackground(new Color(20, 20, 28));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(title);
        top.add(Box.createVerticalStrut(4));
        top.add(subtitle);

        JPanel fields = new JPanel(new GridBagLayout());
        fields.setBackground(new Color(20, 20, 28));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 0;
        gbc.gridx = 0;
        gbc.gridy = 0;

        websiteExeField = styledField();
        serverJarField = styledField();
        clientJarField = styledField();

        addPathRow(fields, gbc, "Website executable", websiteExeField);
        addPathRow(fields, gbc, "Server JAR", serverJarField);
        addPathRow(fields, gbc, "Client JAR", clientJarField);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.setBackground(new Color(20, 20, 28));

        JButton websiteStart = darkButton("▶ Start Website");
        JButton serverStart = darkButton("▶ Start Server");
        JButton clientStart = darkButton("▶ Start Client");
        JButton autoDetect = darkButton("Auto Detect");
        JButton save = darkButton("💾 Save Config");

        websiteStart.addActionListener(e -> startWebsite());
        serverStart.addActionListener(e -> startServer());
        clientStart.addActionListener(e -> startClient());
        autoDetect.addActionListener(e -> {
            autoDetectPaths();
            refreshFields();
            log("Auto-detected paths.");
        });
        save.addActionListener(e -> saveConfig());

        buttons.add(websiteStart);
        buttons.add(serverStart);
        buttons.add(clientStart);
        buttons.add(autoDetect);
        buttons.add(save);

        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.setBackground(new Color(20, 20, 28));
        statusLabel.setForeground(new Color(200, 220, 255));
        statusPanel.add(statusLabel, BorderLayout.WEST);

        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setBackground(new Color(10, 10, 15));
        logArea.setForeground(new Color(180, 255, 180));
        logArea.setCaretColor(Color.WHITE);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setBorder(BorderFactory.createLineBorder(new Color(60, 60, 75)));

        root.add(top, BorderLayout.NORTH);
        root.add(fields, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(0, 8));
        bottom.setBackground(new Color(20, 20, 28));
        bottom.add(buttons, BorderLayout.NORTH);
        bottom.add(statusPanel, BorderLayout.CENTER);
     // IMPORTANT: make log area large and dominant
        scroll.setPreferredSize(new Dimension(800, 260));
        bottom.add(scroll, BorderLayout.SOUTH);
        root.add(bottom, BorderLayout.SOUTH);

        frame.setContentPane(root);
        frame.setVisible(true);
    }

    private JTextField styledField() {
        JTextField field = new JTextField();
        field.setBackground(new Color(30, 30, 40));
        field.setForeground(Color.WHITE);
        field.setCaretColor(Color.WHITE);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 100)),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)
        ));
        return field;
    }

    private JButton darkButton(String text) {
        JButton button = new JButton(text);
        button.setFocusPainted(false);
        button.setForeground(Color.WHITE);
        button.setContentAreaFilled(false);
        button.setOpaque(true);
        button.setBackground(new Color(45, 55, 75));
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(70, 80, 110)),
                BorderFactory.createEmptyBorder(6, 12, 6, 12)
        ));

        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                button.setBackground(new Color(60, 70, 95));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                button.setBackground(new Color(45, 55, 75));
            }
        });

        return button;
    }

    private void addPathRow(JPanel panel, GridBagConstraints gbc, String label, JTextField field) {
        JLabel lbl = new JLabel(label);
        lbl.setForeground(new Color(230, 230, 240));

        gbc.weightx = 0;
        gbc.gridx = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(lbl, gbc);

        gbc.gridx = 1;
        gbc.weightx = 1;
        panel.add(field, gbc);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        actions.setBackground(new Color(20, 20, 28));

        JButton browse = darkButton("...");
        browse.addActionListener(e -> pickFile(field));

        JButton clear = darkButton("Clear");
        clear.addActionListener(e -> field.setText(""));

        actions.add(browse);
        actions.add(clear);

        gbc.gridx = 2;
        gbc.weightx = 0;
        panel.add(actions, gbc);

        gbc.gridy++;
    }

    private void pickFile(JTextField field) {
        JFileChooser chooser = new JFileChooser(rootDir.toFile());
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            field.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void autoDetectPaths() {
        if (isBlankOrMissing(websiteExeField.getText())) {
            Path webExe = rootDir.resolve("web").resolve("unicontroller.exe");
            if (Files.exists(webExe)) {
                websiteExeField.setText(webExe.toString());
            }
        }

        if (isBlankOrMissing(serverJarField.getText())) {
            Path serverJar = findBestJar(rootDir.resolve("server"), "server");
            if (serverJar != null) {
                serverJarField.setText(serverJar.toString());
            }
        }

        if (isBlankOrMissing(clientJarField.getText())) {
            Path clientJar = findBestJar(rootDir.resolve("client"), "client");
            if (clientJar != null) {
                clientJarField.setText(clientJar.toString());
            }
        }
    }

    private boolean isBlankOrMissing(String path) {
        if (path == null || path.trim().isEmpty()) {
            return true;
        }
        return !Files.exists(Paths.get(path.trim()));
    }

    private Path findBestJar(Path folder, String preferredHint) {
        if (!Files.isDirectory(folder)) {
            return null;
        }

        try (Stream<Path> stream = Files.list(folder)) {
            List<Path> jars = stream
                    .filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase().endsWith(".jar"))
                    .collect(Collectors.toList());

            if (jars.isEmpty()) {
                return null;
            }

            String hint = preferredHint == null ? "" : preferredHint.toLowerCase();
            for (Path jar : jars) {
                String name = jar.getFileName().toString().toLowerCase();
                if (name.contains(hint)) {
                    return jar;
                }
            }

            if (jars.size() == 1) {
                return jars.get(0);
            }

            return jars.stream()
                    .max(Comparator.comparingLong(this::lastModifiedSafe))
                    .orElse(jars.get(0));
        } catch (IOException e) {
            log("Auto-detect failed for " + folder + ": " + e.getMessage());
            return null;
        }
    }

    private long lastModifiedSafe(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private void refreshFields() {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                statusLabel.setText("Idle");
            }
        });
    }

    private void startWebsite() {
        executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    Path exe = requireExistingFile(websiteExeField.getText(), "Website executable");
                    List<String> cmd = new ArrayList<String>();
                    cmd.add(exe.toString());
                    cmd.add("-start_both");

                    Process process = startProcess("Website", cmd, exe.getParent());
                    if (process != null) {
                        log("Started Website -> " + exe);
                    }
                } catch (Exception e) {
                    log("Error starting Website: " + e.getMessage());
                }
            }
        });
    }

    private void startServer() {
        executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    Path jar = requireExistingFile(serverJarField.getText(), "Server jar");
                    List<String> cmd = buildJavaJarCommand(jar, "");
                    Process process = startProcess("Server", cmd, jar.getParent());
                    if (process != null) {
                        log("Started Server -> " + jar);
                    }
                } catch (Exception e) {
                    log("Error starting Server: " + e.getMessage());
                }
            }
        });
    }

    private void startClient() {
        if (isProcessAlive("Client")) {
            log("Client already running");
            return;
        }

        executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    Path jar = requireExistingFile(clientJarField.getText(), "Client jar");
                    Path agentJar = getAgentJar();

                    if (!Files.exists(agentJar)) {
                        throw new IllegalArgumentException("agent.jar not found next to client jar: " + agentJar);
                    }

                    List<String> cmd = new ArrayList<String>();
                    cmd.add(findJavaExecutable().toString());
                    cmd.add("-javaagent:" + agentJar.toAbsolutePath());
                    cmd.add("-jar");
                    cmd.add(jar.toString());

                    Process process = startProcess("Client", cmd, jar.getParent());
                    if (process != null) {
                        log("Started Client with Java Agent -> " + jar);
                    }
                } catch (Exception e) {
                    log("Error starting Client: " + e.getMessage());
                }
            }
        });
    }

    private Process startProcess(final String name, final List<String> command, final Path workingDir) throws Exception {
        if (isProcessAlive(name)) {
            log(name + " already running");
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

    private boolean isProcessAlive(String name) {
        Process process = processes.get(name);
        if (process == null) {
            return false;
        }
        if (process.isAlive()) {
            return true;
        }
        processes.remove(name);
        return false;
    }

    private List<String> buildJavaJarCommand(Path jar, String args) {
        List<String> cmd = new ArrayList<String>();
        cmd.add(findJavaExecutable().toString());
        cmd.add("-jar");
        cmd.add(jar.toString());
        cmd.addAll(splitArgs(args));
        return cmd;
    }

    private Path findJavaExecutable() {
        String javaHome = System.getProperty("java.home");
        Path bin = Paths.get(javaHome, "bin");

        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            Path javaw = bin.resolve("javaw.exe");
            if (Files.exists(javaw)) {
                return javaw;
            }
            Path java = bin.resolve("java.exe");
            if (Files.exists(java)) {
                return java;
            }
        } else {
            Path java = bin.resolve("java");
            if (Files.exists(java)) {
                return java;
            }
        }

        return bin.resolve("java");
    }

    private List<String> splitArgs(String args) {
        if (args == null || args.trim().isEmpty()) {
            return new ArrayList<String>();
        }
        return new ArrayList<String>(Arrays.asList(args.trim().split("\\s+")));
    }

    private Path requireExistingFile(String path, String label) {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " path is empty");
        }

        Path p = Paths.get(path.trim());
        if (!Files.exists(p)) {
            throw new IllegalArgumentException(label + " not found: " + p);
        }
        return p;
    }

    private void streamLogs(final String name, final Process process) {
        executor.submit(new Runnable() {
            @Override
            public void run() {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {

                    String line;
                    while ((line = reader.readLine()) != null) {
                        log("[" + name + "] " + line);
                    }
                } catch (IOException e) {
                    log("Log stream ended for " + name + ": " + e.getMessage());
                }
            }
        });
    }

    private void watchExit(final String name, final Process process) {
        executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    int code = process.waitFor();
                    processes.remove(name);
                    log(name + " exited with code " + code);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

    private void log(final String msg) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {

                logArea.append("[" + java.time.LocalTime.now().withNano(0) + "] " + msg + "\n");

                // auto-scroll
                logArea.setCaretPosition(logArea.getDocument().getLength());

                // trim overflow
                String[] lines = logArea.getText().split("\n");

                if (lines.length > MAX_LOG_LINES) {
                    StringBuilder sb = new StringBuilder();

                    for (int i = lines.length - MAX_LOG_LINES; i < lines.length; i++) {
                        sb.append(lines[i]).append("\n");
                    }

                    logArea.setText(sb.toString());
                }
            }
        });
    }

    private void saveConfig() {
        try {
            Properties props = new Properties();
            props.setProperty("websiteExe", websiteExeField.getText());
            props.setProperty("serverJar", serverJarField.getText());
            props.setProperty("clientJar", clientJarField.getText());

            try (Writer writer = Files.newBufferedWriter(configFile, StandardCharsets.UTF_8)) {
                props.store(writer, "RS2 Progressive Launcher");
            }

            log("Saved config to " + configFile.getFileName());
        } catch (Exception e) {
            log("Save failed: " + e.getMessage());
        }
    }

    private void loadConfig() {
        if (!Files.exists(configFile)) {
            return;
        }

        try {
            Properties props = new Properties();
            try (Reader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
                props.load(reader);
            }

            websiteExeField.setText(props.getProperty("websiteExe", ""));
            serverJarField.setText(props.getProperty("serverJar", ""));
            clientJarField.setText(props.getProperty("clientJar", ""));
        } catch (Exception e) {
            log("Load failed: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                new Launcher();
            }
        });
    }
}