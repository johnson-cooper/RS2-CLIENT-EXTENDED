package agent;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A sidebar panel docked to the RIGHT of the game frame.
 *
 * Instead of overlaying the canvas, we widen the frame by SIDEBAR_WIDTH
 * and place the game canvas on the left, sidebar on the right — no overlap.
 *
 * Each icon button expands an inline settings panel directly below it.
 *
 * Design: dark industrial, amber accents, monospace icons.
 */
public class ClientSidebar {

    public static final int SIDEBAR_WIDTH = 180;
    private static final int ICON_COL     = 36;  // width of the icon strip
    private static final int BTN_SIZE     = 32;
    private static final int BTN_GAP      = 4;

    // Colors
    private static final Color BG_SIDEBAR  = new Color(14, 14, 16);
    private static final Color BG_PANEL    = new Color(22, 22, 26);
    private static final Color BG_HOVER    = new Color(40, 40, 46);
    private static final Color ACCENT      = new Color(255, 185, 0);
    private static final Color ICON_NORMAL = new Color(160, 160, 168);
    private static final Color BORDER_COL  = new Color(50, 50, 58);
    private static final Color TEXT_COL    = new Color(200, 200, 208);

    private static JPanel  sidebar;
    private static Frame   gameFrame;
    private static boolean installed = false;

    // Ordered map of tool name → ToolEntry
    private static final Map<String, ToolEntry> tools = new LinkedHashMap<>();

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public static void startWatcher() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    if (!installed) {
                        for (Frame f : Frame.getFrames()) {
                            if (f != null && f.isVisible() && f.getWidth() > 100) {
                                SwingUtilities.invokeLater(() -> install(f));
                                break;
                            }
                        }
                    }
                    Thread.sleep(500);
                } catch (Throwable ignored) {}
            }
        }, "SidebarWatcher");
        t.setDaemon(true);
        t.start();
        System.out.println("[Sidebar] watcher started");
    }

    // -------------------------------------------------------------------------
    // Installation
    // -------------------------------------------------------------------------

    private static void install(Frame f) {
        if (installed) return;
        installed = true;
        gameFrame = f;

        System.out.println("[Sidebar] installing on: " + f.getTitle());

        // Widen the frame to make room for the sidebar
        int newW = f.getWidth() + SIDEBAR_WIDTH;
        f.setSize(newW, f.getHeight());
        f.setResizable(true);

        // Build sidebar panel
        sidebar = new JPanel(null) {
            @Override protected void paintComponent(Graphics g) {
                g.setColor(BG_SIDEBAR);
                g.fillRect(0, 0, getWidth(), getHeight());
                // Left border line
                g.setColor(BORDER_COL);
                g.drawLine(0, 0, 0, getHeight());
            }
        };
        sidebar.setOpaque(false);

        // Register default tools
        registerTools();

        // Build layout
        rebuildSidebar();

        // If it's a JFrame, use the content pane; otherwise add directly
        if (f instanceof JFrame) {
            JFrame jf = (JFrame) f;
            Container cp = jf.getContentPane();
            cp.setLayout(null);

            // Reposition game canvas to left, sidebar to right
            Component[] kids = cp.getComponents();
            for (Component c : kids) cp.remove(c);
            for (Component c : kids) {
                if (c != sidebar) cp.add(c);
            }
            cp.add(sidebar);
            layoutFrame(jf);

            jf.addComponentListener(new ComponentAdapter() {
                @Override public void componentResized(ComponentEvent e) { layoutFrame(jf); }
            });
        } else {
            f.setLayout(null);
            f.add(sidebar);
            layoutFrameAWT(f);

            f.addComponentListener(new ComponentAdapter() {
                @Override public void componentResized(ComponentEvent e) { layoutFrameAWT(f); }
            });
        }

        f.revalidate();
        f.repaint();
        System.out.println("[Sidebar] installed");
    }

    // -------------------------------------------------------------------------
    // Layout
    // -------------------------------------------------------------------------

    private static void layoutFrame(JFrame jf) {
        Container cp = jf.getContentPane();
        Insets ins = jf.getInsets();
        int w = jf.getWidth()  - ins.left - ins.right;
        int h = jf.getHeight() - ins.top  - ins.bottom;

        int gameW = w - SIDEBAR_WIDTH;

        for (Component c : cp.getComponents()) {
            if (c == sidebar) {
                sidebar.setBounds(gameW, 0, SIDEBAR_WIDTH, h);
            } else {
                // Game canvas — left side, full height
                c.setBounds(0, 0, gameW, h);
                ClientConfig.setInnerSize(gameW, h);
            }
        }
        rebuildSidebar();
    }

    private static void layoutFrameAWT(Frame f) {
        Insets ins = f.getInsets();
        int w = f.getWidth()  - ins.left - ins.right;
        int h = f.getHeight() - ins.top  - ins.bottom;
        int gameW = w - SIDEBAR_WIDTH;

        for (Component c : f.getComponents()) {
            if (c == sidebar) {
                sidebar.setBounds(ins.left + gameW, ins.top, SIDEBAR_WIDTH, h);
            } else {
                c.setBounds(ins.left, ins.top, gameW, h);
                ClientConfig.setInnerSize(gameW, h);
            }
        }
        rebuildSidebar();
    }

    // -------------------------------------------------------------------------
    // Tool registration
    // -------------------------------------------------------------------------

    private static void registerTools() {
        // Tile Markers
        ToolEntry markers = new ToolEntry("⬡", "Tile Markers");
        markers.addSetting(new ToggleSetting("Enabled",
                TileMarkerInput::isEnabled,
                TileMarkerInput::setEnabled));
        markers.addSetting(new ColorSetting("Marker colour",
                TileMarkerInput.getCurrentColor(),
                c -> TileMarkerInput.setCurrentColor(
                        new Color(c.getRed(), c.getGreen(), c.getBlue(), 180))));
        markers.addSetting(new ButtonSetting("Clear all markers", () -> {
            int r = JOptionPane.showConfirmDialog(sidebar,
                    "Clear all tile markers?", "Confirm", JOptionPane.YES_NO_OPTION);
            if (r == JOptionPane.YES_OPTION) TileMarkerStore.clear();
        }));
        markers.addSetting(new LabelSetting("Shift + right-click to place"));
        tools.put("markers", markers);
    }

    // -------------------------------------------------------------------------
    // Sidebar build
    // -------------------------------------------------------------------------

    private static void rebuildSidebar() {
        if (sidebar == null) return;
        sidebar.removeAll();

        int y = 8;
        for (ToolEntry entry : tools.values()) {
            // Icon button
            IconButton btn = new IconButton(entry);
            btn.setBounds(2, y, BTN_SIZE, BTN_SIZE);
            sidebar.add(btn);
            y += BTN_SIZE + BTN_GAP;

            // Settings panel (shown when expanded)
            if (entry.expanded) {
                JPanel panel = buildSettingsPanel(entry);
                int ph = panel.getPreferredSize().height;
                panel.setBounds(2, y, SIDEBAR_WIDTH - 4, ph);
                sidebar.add(panel);
                y += ph + BTN_GAP;
            }
        }

        sidebar.revalidate();
        sidebar.repaint();
    }

    private static JPanel buildSettingsPanel(ToolEntry entry) {
        JPanel p = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(BG_PANEL);
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 6, 6));
                g2.setColor(BORDER_COL);
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth()-1, getHeight()-1, 6, 6));
                g2.dispose();
            }
        };
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        p.setBorder(new EmptyBorder(6, 8, 6, 8));

        // Title
        JLabel title = new JLabel(entry.label);
        title.setForeground(ACCENT);
        title.setFont(new Font("Monospaced", Font.BOLD, 11));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(title);
        p.add(Box.createVerticalStrut(6));

        for (Setting s : entry.settings) {
            Component row = s.buildRow();
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
            p.add(row);
            p.add(Box.createVerticalStrut(4));
        }

        p.setPreferredSize(new Dimension(SIDEBAR_WIDTH - 4,
                title.getPreferredSize().height + entry.settings.size() * 30 + 20));
        return p;
    }

    // -------------------------------------------------------------------------
    // Icon button
    // -------------------------------------------------------------------------

    private static class IconButton extends JComponent {
        private final ToolEntry entry;
        private boolean hovered;

        IconButton(ToolEntry entry) {
            this.entry = entry;
            setToolTipText(entry.label);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hovered = true;  repaint(); }
                @Override public void mouseExited (MouseEvent e) { hovered = false; repaint(); }
                @Override public void mousePressed(MouseEvent e) {
                    if (e.getButton() != MouseEvent.BUTTON1) return;
                    entry.expanded = !entry.expanded;
                    rebuildSidebar();
                }
            });
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();

            if (entry.expanded) {
                g2.setColor(new Color(255, 185, 0, 25));
                g2.fillRoundRect(0, 0, w, h, 6, 6);
                g2.setColor(ACCENT);
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawRoundRect(0, 0, w-1, h-1, 6, 6);
                // Active left bar
                g2.fillRect(0, 4, 2, h - 8);
            } else if (hovered) {
                g2.setColor(BG_HOVER);
                g2.fillRoundRect(0, 0, w, h, 6, 6);
            }

            g2.setFont(new Font("Dialog", Font.PLAIN, 15));
            FontMetrics fm = g2.getFontMetrics();
            int tx = (w - fm.stringWidth(entry.icon)) / 2;
            int ty = (h + fm.getAscent() - fm.getDescent()) / 2;
            g2.setColor(entry.expanded ? ACCENT : (hovered ? Color.WHITE : ICON_NORMAL));
            g2.drawString(entry.icon, tx, ty);
            g2.dispose();
        }
    }

    // -------------------------------------------------------------------------
    // Settings types
    // -------------------------------------------------------------------------

    interface Setting {
        Component buildRow();
    }

    static class LabelSetting implements Setting {
        private final String text;
        LabelSetting(String text) { this.text = text; }
        @Override public Component buildRow() {
            JLabel l = new JLabel(text);
            l.setForeground(new Color(120, 120, 130));
            l.setFont(new Font("Monospaced", Font.PLAIN, 10));
            l.setAlignmentX(Component.LEFT_ALIGNMENT);
            return l;
        }
    }

    static class ButtonSetting implements Setting {
        private final String label;
        private final Runnable action;
        ButtonSetting(String label, Runnable action) { this.label = label; this.action = action; }
        @Override public Component buildRow() {
            JButton b = new JButton(label) {
                @Override protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(getModel().isPressed()
                            ? new Color(80, 30, 0)
                            : getModel().isRollover() ? new Color(60, 25, 0) : new Color(35, 35, 40));
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 5, 5);
                    g2.setColor(BORDER_COL);
                    g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 5, 5);
                    g2.setColor(TEXT_COL);
                    g2.setFont(getFont());
                    FontMetrics fm = g2.getFontMetrics();
                    g2.drawString(getText(),
                            (getWidth() - fm.stringWidth(getText())) / 2,
                            (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
                    g2.dispose();
                }
            };
            b.setFont(new Font("Monospaced", Font.PLAIN, 10));
            b.setContentAreaFilled(false);
            b.setBorderPainted(false);
            b.setFocusPainted(false);
            b.setAlignmentX(Component.LEFT_ALIGNMENT);
            b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
            b.addActionListener(ev -> action.run());
            return b;
        }
    }

    static class ColorSetting implements Setting {
        private final String label;
        private Color current;
        private final java.util.function.Consumer<Color> onChange;
        private JPanel swatch;

        ColorSetting(String label, Color initial, java.util.function.Consumer<Color> onChange) {
            this.label = label; this.current = initial; this.onChange = onChange;
        }

        @Override public Component buildRow() {
            JPanel row = new JPanel(new BorderLayout(6, 0));
            row.setOpaque(false);
            row.setAlignmentX(Component.LEFT_ALIGNMENT);

            JLabel lbl = new JLabel(label);
            lbl.setForeground(TEXT_COL);
            lbl.setFont(new Font("Monospaced", Font.PLAIN, 10));

            swatch = new JPanel() {
                @Override protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    // Checkerboard background (shows alpha)
                    g2.setColor(Color.GRAY);
                    g2.fillRect(0, 0, getWidth(), getHeight());
                    g2.setColor(Color.LIGHT_GRAY);
                    for (int i = 0; i < getWidth(); i += 4)
                        for (int j = 0; j < getHeight(); j += 4)
                            if ((i + j) % 8 == 0) g2.fillRect(i, j, 4, 4);
                    g2.setColor(current);
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 4, 4);
                    g2.setColor(BORDER_COL);
                    g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 4, 4);
                    g2.dispose();
                }
            };
            swatch.setPreferredSize(new Dimension(20, 16));
            swatch.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            swatch.addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    // Use a simple HSB slider dialog instead of JColorChooser swatch
                    // to avoid the JDK 8 SwatchPanel array crash
                    showColorDialog();
                }
            });

            row.add(lbl, BorderLayout.CENTER);
            row.add(swatch, BorderLayout.EAST);
            return row;
        }

        private void showColorDialog() {
            // Build a minimal color dialog with RGB sliders — avoids the
            // JColorChooser swatch panel crash entirely
            JDialog dlg = new JDialog((Window) null, "Marker Colour",
                    Dialog.ModalityType.APPLICATION_MODAL);
            dlg.setSize(260, 200);
            dlg.setLocationRelativeTo(sidebar);
            dlg.getContentPane().setBackground(BG_PANEL);
            dlg.getContentPane().setLayout(new BorderLayout(8, 8));

            JPanel sliders = new JPanel(new GridLayout(4, 2, 4, 4));
            sliders.setOpaque(false);
            sliders.setBorder(new EmptyBorder(10, 10, 4, 10));

            int[] rgba = {current.getRed(), current.getGreen(),
                          current.getBlue(), current.getAlpha()};
            String[] names = {"R", "G", "B", "A"};
            JSlider[] ss = new JSlider[4];

            JPanel preview = new JPanel() {
                @Override protected void paintComponent(Graphics g) {
                    g.setColor(new Color(rgba[0], rgba[1], rgba[2], rgba[3]));
                    g.fillRect(0, 0, getWidth(), getHeight());
                }
            };
            preview.setPreferredSize(new Dimension(0, 24));

            for (int i = 0; i < 4; i++) {
                int idx = i;
                JLabel lbl = new JLabel(names[i]);
                lbl.setForeground(TEXT_COL);
                lbl.setFont(new Font("Monospaced", Font.BOLD, 11));
                ss[i] = new JSlider(0, 255, rgba[i]);
                ss[i].addChangeListener(ev -> {
                    rgba[idx] = ss[idx].getValue();
                    preview.repaint();
                });
                sliders.add(lbl);
                sliders.add(ss[i]);
            }

            JButton ok = new JButton("OK");
            ok.addActionListener(ev -> {
                current = new Color(rgba[0], rgba[1], rgba[2], rgba[3]);
                onChange.accept(current);
                if (swatch != null) swatch.repaint();
                dlg.dispose();
            });
            JButton cancel = new JButton("Cancel");
            cancel.addActionListener(ev -> dlg.dispose());

            JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
            btns.setOpaque(false);
            btns.add(cancel);
            btns.add(ok);

            dlg.getContentPane().add(sliders, BorderLayout.CENTER);
            dlg.getContentPane().add(preview, BorderLayout.NORTH);
            dlg.getContentPane().add(btns,    BorderLayout.SOUTH);
            dlg.setVisible(true);
        }
    }


    static class ToggleSetting implements Setting {
        private final String label;
        private final java.util.function.BooleanSupplier getter;
        private final java.util.function.Consumer<Boolean> setter;

        ToggleSetting(String label, java.util.function.BooleanSupplier getter,
                      java.util.function.Consumer<Boolean> setter) {
            this.label = label; this.getter = getter; this.setter = setter;
        }

        @Override public Component buildRow() {
            JPanel row = new JPanel(new BorderLayout(6, 0));
            row.setOpaque(false);
            row.setAlignmentX(Component.LEFT_ALIGNMENT);

            JLabel lbl = new JLabel(label);
            lbl.setForeground(TEXT_COL);
            lbl.setFont(new Font("Monospaced", Font.PLAIN, 10));

            // Toggle button — shows ON/OFF with amber/grey accent
            JButton toggle = new JButton() {
                @Override protected void paintComponent(Graphics g) {
                    boolean on = getter.getAsBoolean();
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(on ? new Color(60, 40, 0) : new Color(35, 35, 40));
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 5, 5);
                    g2.setColor(on ? ACCENT : BORDER_COL);
                    g2.setStroke(new BasicStroke(1.5f));
                    g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 5, 5);
                    g2.setColor(on ? ACCENT : new Color(120, 120, 130));
                    g2.setFont(new Font("Monospaced", Font.BOLD, 9));
                    FontMetrics fm = g2.getFontMetrics();
                    String txt = on ? "ON" : "OFF";
                    g2.drawString(txt, (getWidth() - fm.stringWidth(txt)) / 2,
                            (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
                    g2.dispose();
                }
            };
            toggle.setPreferredSize(new Dimension(36, 16));
            toggle.setContentAreaFilled(false);
            toggle.setBorderPainted(false);
            toggle.setFocusPainted(false);
            toggle.addActionListener(ev -> {
                setter.accept(!getter.getAsBoolean());
                toggle.repaint();
            });

            row.add(lbl, BorderLayout.CENTER);
            row.add(toggle, BorderLayout.EAST);
            return row;
        }
    }

    // -------------------------------------------------------------------------
    // Data
    // -------------------------------------------------------------------------

    static class ToolEntry {
        final String icon;
        final String label;
        final java.util.List<Setting> settings = new java.util.ArrayList<>();
        boolean expanded = false;

        ToolEntry(String icon, String label) { this.icon = icon; this.label = label; }
        void addSetting(Setting s) { settings.add(s); }
    }
}