package agent;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;

/**
 * Generic sidebar host. Knows nothing about specific plugins —
 * it reads PluginRegistry and builds an icon strip + expandable panels.
 */
public class ClientSidebar {

    public static final int SIDEBAR_WIDTH = 180;
    private static final int BTN_SIZE     = 32;
    private static final int BTN_GAP      = 4;

    private static final Color BG_SIDEBAR  = new Color(14, 14, 16);
    private static final Color BG_PANEL    = new Color(22, 22, 26);
    private static final Color BG_HOVER    = new Color(40, 40, 46);
    private static final Color ACCENT      = new Color(255, 185, 0);
    private static final Color ICON_NORMAL = new Color(160, 160, 168);
    private static final Color BORDER_COL  = new Color(50, 50, 58);

    private static JPanel  sidebar;
    private static boolean installed = false;

    // Expanded state per plugin (by index)
    private static boolean[] expanded = new boolean[0];

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

    private static void install(Frame f) {
        if (installed) return;
        installed = true;
        System.out.println("[Sidebar] installing on: " + f.getTitle());

        expanded = new boolean[PluginRegistry.getAll().size()];

        f.setSize(f.getWidth() + SIDEBAR_WIDTH, f.getHeight());
        f.setResizable(true);

        sidebar = new JPanel(null) {
            @Override protected void paintComponent(Graphics g) {
                g.setColor(BG_SIDEBAR);
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(BORDER_COL);
                g.drawLine(0, 0, 0, getHeight());
            }
        };
        sidebar.setOpaque(false);

        rebuildSidebar();

        if (f instanceof JFrame) {
            JFrame jf = (JFrame) f;
            Container cp = jf.getContentPane();
            cp.setLayout(null);
            Component[] kids = cp.getComponents();
            for (Component c : kids) cp.remove(c);
            for (Component c : kids) if (c != sidebar) cp.add(c);
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

    private static void layoutFrame(JFrame jf) {
        Container cp = jf.getContentPane();
        Insets ins = jf.getInsets();
        int w = jf.getWidth() - ins.left - ins.right;
        int h = jf.getHeight() - ins.top - ins.bottom;
        int gameW = w - SIDEBAR_WIDTH;
        for (Component c : cp.getComponents()) {
            if (c == sidebar) sidebar.setBounds(gameW, 0, SIDEBAR_WIDTH, h);
            else { c.setBounds(0, 0, gameW, h); ClientConfig.setInnerSize(gameW, h); }
        }
        rebuildSidebar();
    }

    private static void layoutFrameAWT(Frame f) {
        Insets ins = f.getInsets();
        int w = f.getWidth() - ins.left - ins.right;
        int h = f.getHeight() - ins.top - ins.bottom;
        int gameW = w - SIDEBAR_WIDTH;
        for (Component c : f.getComponents()) {
            if (c == sidebar) sidebar.setBounds(ins.left + gameW, ins.top, SIDEBAR_WIDTH, h);
            else { c.setBounds(ins.left, ins.top, gameW, h); ClientConfig.setInnerSize(gameW, h); }
        }
        rebuildSidebar();
    }

    static void rebuildSidebar() {
        if (sidebar == null) return;
        sidebar.removeAll();

        java.util.List<Plugin> plugins = PluginRegistry.getAll();
        if (expanded.length != plugins.size()) {
            boolean[] next = new boolean[plugins.size()];
            System.arraycopy(expanded, 0, next, 0, Math.min(expanded.length, next.length));
            expanded = next;
        }

        int y = 8;
        for (int i = 0; i < plugins.size(); i++) {
            final Plugin plugin = plugins.get(i);
            final int idx = i;

            // Icon button
            JComponent btn = buildIconButton(plugin, idx);
            btn.setBounds(2, y, BTN_SIZE, BTN_SIZE);
            sidebar.add(btn);
            y += BTN_SIZE + BTN_GAP;

            // Settings panel
            if (expanded[idx]) {
                JPanel panel = wrapPanel(plugin);
                int ph = panel.getPreferredSize().height;
                panel.setBounds(2, y, SIDEBAR_WIDTH - 4, ph);
                sidebar.add(panel);
                y += ph + BTN_GAP;
            }
        }

        sidebar.revalidate();
        sidebar.repaint();
    }

    private static JComponent buildIconButton(Plugin plugin, int idx) {
        return new JComponent() {
            private boolean hovered;
            {
                setToolTipText(plugin.getName());
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                addMouseListener(new MouseAdapter() {
                    @Override public void mouseEntered(MouseEvent e) { hovered = true;  repaint(); }
                    @Override public void mouseExited (MouseEvent e) { hovered = false; repaint(); }
                    @Override public void mousePressed(MouseEvent e) {
                        if (e.getButton() != MouseEvent.BUTTON1) return;
                        expanded[idx] = !expanded[idx];
                        rebuildSidebar();
                    }
                });
            }
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth(), h = getHeight();
                boolean on = expanded[idx];
                if (on) {
                    g2.setColor(new Color(255, 185, 0, 25));
                    g2.fillRoundRect(0, 0, w, h, 6, 6);
                    g2.setColor(ACCENT);
                    g2.setStroke(new BasicStroke(1.5f));
                    g2.drawRoundRect(0, 0, w-1, h-1, 6, 6);
                    g2.fillRect(0, 4, 2, h - 8);
                } else if (hovered) {
                    g2.setColor(BG_HOVER);
                    g2.fillRoundRect(0, 0, w, h, 6, 6);
                }
                g2.setFont(new Font("Dialog", Font.PLAIN, 15));
                FontMetrics fm = g2.getFontMetrics();
                int tx = (w - fm.stringWidth(plugin.getIcon())) / 2;
                int ty = (h + fm.getAscent() - fm.getDescent()) / 2;
                g2.setColor(on ? ACCENT : (hovered ? Color.WHITE : ICON_NORMAL));
                g2.drawString(plugin.getIcon(), tx, ty);
                g2.dispose();
            }
        };
    }

    private static JPanel wrapPanel(Plugin plugin) {
        JPanel wrapper = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(BG_PANEL);
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 6, 6));
                g2.setColor(BORDER_COL);
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth()-1, getHeight()-1, 6, 6));
                g2.dispose();
            }
        };
        wrapper.setLayout(new BoxLayout(wrapper, BoxLayout.Y_AXIS));
        wrapper.setOpaque(false);

        // Title
        JLabel title = new JLabel(plugin.getName());
        title.setForeground(ACCENT);
        title.setFont(new Font("Monospaced", Font.BOLD, 11));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        title.setBorder(javax.swing.BorderFactory.createEmptyBorder(6, 8, 4, 8));
        wrapper.add(title);

        // Plugin's own panel
        JPanel content = plugin.buildPanel();
        wrapper.add(content);

        wrapper.setPreferredSize(new Dimension(
                SIDEBAR_WIDTH - 4,
                title.getPreferredSize().height + content.getPreferredSize().height + 16));
        return wrapper;
    }
}