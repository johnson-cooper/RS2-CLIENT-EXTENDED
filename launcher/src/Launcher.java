import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Launcher {

    // ── Constants ─────────────────────────────────────────────────────────────
    private static final int MAX_LOG_LINES = 2000;

    // ── Infrastructure ────────────────────────────────────────────────────────
    private final Path rootDir     = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    private final Path configFile  = rootDir.resolve("config.properties");
    private final PathDetector     detector = new PathDetector(this::log);
    private final ProcessManager   procMgr  = new ProcessManager(this::log);

    // ── UI components ─────────────────────────────────────────────────────────
    // FIX: fields are only assigned once, inside setupUI() via styledField().
    //      The old code assigned `new JTextField()` at declaration, then re-assigned
    //      in setupUI(), silently wasting the first allocation.
    private JFrame     frame;
    private JTextField websiteExeField;
    private JTextField serverJarField;
    private JTextField clientJarField;
    private JTextArea  logArea;
    private JLabel     statusLabel;

    // ── Constructor ───────────────────────────────────────────────────────────
    public Launcher() {
        setupUI();
        loadConfig();
        autoDetectPaths();
        refreshFields();
    }

    // ── UI setup ──────────────────────────────────────────────────────────────
    private void setupUI() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        frame = new JFrame("RS2 Progressive Launcher");

        // FIX: replace EXIT_ON_CLOSE with DO_NOTHING + window listener so we can
        //      shut down processes and the executor cleanly before the JVM exits.
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                procMgr.shutdownAll();   // FIX: kills orphan processes, shuts down executor
                frame.dispose();
                System.exit(0);
            }
        });

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

        // FIX: assign fields exactly once here
        websiteExeField = styledField();
        serverJarField  = styledField();
        clientJarField  = styledField();

        // FIX: add real-time validation border feedback
        addPathValidation(websiteExeField);
        addPathValidation(serverJarField);
        addPathValidation(clientJarField);

        JPanel fields = new JPanel(new GridBagLayout());
        fields.setBackground(new Color(20, 20, 28));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets  = new Insets(6, 6, 6, 6);
        gbc.fill    = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 0;
        gbc.gridx   = 0;
        gbc.gridy   = 0;

        addPathRow(fields, gbc, "Website executable", websiteExeField);
        addPathRow(fields, gbc, "Server JAR",         serverJarField);
        addPathRow(fields, gbc, "Client JAR",         clientJarField);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.setBackground(new Color(20, 20, 28));

        JButton websiteStart = darkButton("▶ Start Website");
        JButton serverStart  = darkButton("▶ Start Server");
        JButton clientStart  = darkButton("▶ Start Client");
        JButton autoDetect   = darkButton("Auto Detect");
        JButton save         = darkButton("💾 Save Config");

        websiteStart.addActionListener(e -> startWebsite());
        serverStart .addActionListener(e -> startServer());
        clientStart .addActionListener(e -> startClient());
        autoDetect  .addActionListener(e -> {
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
        statusLabel = new JLabel("Idle");
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
        scroll.setPreferredSize(new Dimension(800, 260));
        bottom.add(scroll, BorderLayout.SOUTH);
        root.add(bottom, BorderLayout.SOUTH);

        frame.setContentPane(root);
        frame.setVisible(true);
    }

    // ── Helpers: styled widgets ───────────────────────────────────────────────
    private JTextField styledField() {
        JTextField field = new JTextField();
        field.setBackground(new Color(30, 30, 40));
        field.setForeground(Color.WHITE);
        field.setCaretColor(Color.WHITE);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 100)),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)));
        return field;
    }

    /**
     * FIX: attach a DocumentListener so the border turns red the moment the
     * typed path does not resolve to an existing file, giving the user
     * immediate feedback without waiting until they click a launch button.
     */
    private void addPathValidation(JTextField field) {
        field.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            private void validate() {
                String text = field.getText().trim();
                boolean valid = text.isEmpty() || Files.exists(Paths.get(text));
                Color borderColor = valid ? new Color(80, 80, 100) : new Color(200, 60, 60);
                field.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(borderColor),
                        BorderFactory.createEmptyBorder(4, 6, 4, 6)));
            }
            @Override public void insertUpdate (javax.swing.event.DocumentEvent e) { validate(); }
            @Override public void removeUpdate (javax.swing.event.DocumentEvent e) { validate(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { validate(); }
        });
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
                BorderFactory.createEmptyBorder(6, 12, 6, 12)));

        button.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { button.setBackground(new Color(60, 70, 95)); }
            @Override public void mouseExited (MouseEvent e) { button.setBackground(new Color(45, 55, 75)); }
        });
        return button;
    }

    private void addPathRow(JPanel panel, GridBagConstraints gbc, String label, JTextField field) {
        JLabel lbl = new JLabel(label);
        lbl.setForeground(new Color(230, 230, 240));

        gbc.weightx = 0; gbc.gridx = 0; gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(lbl, gbc);

        gbc.gridx = 1; gbc.weightx = 1;
        panel.add(field, gbc);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        actions.setBackground(new Color(20, 20, 28));

        JButton browse = darkButton("...");
        browse.addActionListener(e -> pickFile(field));

        JButton clear = darkButton("Clear");
        clear.addActionListener(e -> field.setText(""));

        actions.add(browse);
        actions.add(clear);

        gbc.gridx = 2; gbc.weightx = 0;
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

    // ── Path auto-detection ───────────────────────────────────────────────────
    private void autoDetectPaths() {
        PathDetector.DetectedPaths found = detector.detectDefaults(rootDir);

        if (detector.isBlankOrMissing(websiteExeField.getText()) && found.websiteExe != null) {
            websiteExeField.setText(found.websiteExe.toString());
        }
        if (detector.isBlankOrMissing(serverJarField.getText()) && found.serverJar != null) {
            serverJarField.setText(found.serverJar.toString());
        }
        if (detector.isBlankOrMissing(clientJarField.getText()) && found.clientJar != null) {
            clientJarField.setText(found.clientJar.toString());
        }
    }

    private void refreshFields() {
        SwingUtilities.invokeLater(() -> statusLabel.setText("Idle"));
    }

    // ── Launch actions ────────────────────────────────────────────────────────
    private void startWebsite() {
        new Thread(() -> {
            try {
                Path exe = detector.requireExistingFile(websiteExeField.getText(), "Website executable");
                List<String> cmd = new ArrayList<>();
                cmd.add(exe.toString());
                cmd.add("-start_both");
                Process p = procMgr.startProcess("Website", cmd, exe.getParent());
                if (p != null) log("Started Website -> " + exe);
            } catch (Exception e) {
                log("Error starting Website: " + e.getMessage());
            }
        }).start();
    }

    private void startServer() {
        new Thread(() -> {
            try {
                Path jar = detector.requireExistingFile(serverJarField.getText(), "Server jar");
                // FIX: removed the empty-string dead-code args parameter; build command directly
                List<String> cmd = new ArrayList<>();
                cmd.add(detector.findJavaExecutable().toString());
                cmd.add("-jar");
                cmd.add(jar.toString());
                Process p = procMgr.startProcess("Server", cmd, jar.getParent());
                if (p != null) log("Started Server -> " + jar);
            } catch (Exception e) {
                log("Error starting Server: " + e.getMessage());
            }
        }).start();
    }

    private void startClient() {
        if (procMgr.isProcessAlive("Client")) {
            log("Client already running");
            return;
        }

        new Thread(() -> {
            try {
                Path jar      = detector.requireExistingFile(clientJarField.getText(), "Client jar");
                Path agentJar = jar.getParent().resolve("agent.jar");

                if (!java.nio.file.Files.exists(agentJar)) {
                    throw new IllegalArgumentException("agent.jar not found next to client jar: " + agentJar);
                }

                List<String> cmd = new ArrayList<>();
                cmd.add(detector.findJavaExecutable().toString());
                cmd.add("-javaagent:" + agentJar.toAbsolutePath());
                // Only add --add-opens on Java 9+; Java 8 does not recognise the flag
                if (jvmMajorVersion() >= 9) {
                    cmd.add("--add-opens");
                    cmd.add("java.desktop/java.awt.event=ALL-UNNAMED");
                }
                cmd.add("-jar");
                cmd.add(jar.toString());

                Process p = procMgr.startProcess("Client", cmd, jar.getParent());
                if (p != null) log("Started Client with Java Agent -> " + jar);
            } catch (Exception e) {
                log("Error starting Client: " + e.getMessage());
            }
        }).start();
    }

    // ── Config persistence ────────────────────────────────────────────────────
    private void saveConfig() {
        try {
            LauncherConfig.save(configFile,
                    websiteExeField.getText(),
                    serverJarField.getText(),
                    clientJarField.getText());
            log("Saved config to " + configFile.getFileName());
        } catch (Exception e) {
            log("Save failed: " + e.getMessage());
        }
    }

    private void loadConfig() {
        try {
            LauncherConfig.ConfigData cfg = LauncherConfig.load(configFile);
            websiteExeField.setText(cfg.websiteExe);
            serverJarField .setText(cfg.serverJar);
            clientJarField .setText(cfg.clientJar);
        } catch (Exception e) {
            log("Load failed: " + e.getMessage());
        }
    }

    // ── Logging ───────────────────────────────────────────────────────────────
    /**
     * FIX: use Document API instead of getText().split("\n") so trimming is
     * O(removed_chars) rather than O(total_buffer) on every log line.
     */
    private void log(String msg) {
        SwingUtilities.invokeLater(() -> {
            logArea.append("[" + java.time.LocalTime.now().withNano(0) + "] " + msg + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());

            int excess = logArea.getLineCount() - 1 - MAX_LOG_LINES;
            if (excess > 0) {
                try {
                    Document doc    = logArea.getDocument();
                    int      offset = logArea.getLineEndOffset(excess - 1);
                    doc.remove(0, offset);
                } catch (BadLocationException ignored) {}
            }
        });
    }

    // ── JVM utilities ─────────────────────────────────────────────────────────
    /**
     * Returns the major version of the running JVM (8, 11, 17, 21, …).
     * Uses "java.version" which is available on all versions:
     *   Java 8  → "1.8.0_xxx"  → 8
     *   Java 9+ → "11.0.2"     → 11
     */
    private static int jvmMajorVersion() {
        String v = System.getProperty("java.version", "1.8");
        if (v.startsWith("1.")) {
            return Integer.parseInt(v.split("\\.")[1]);
        }
        return Integer.parseInt(v.split("[.\\-+]")[0]);
    }

    // ── Entry point ───────────────────────────────────────────────────────────
    public static void main(String[] args) {
        SwingUtilities.invokeLater(Launcher::new);
    }
}