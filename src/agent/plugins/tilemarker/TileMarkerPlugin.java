package agent.plugins.tilemarker;

import agent.*;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;

/**
 * Plugin that provides tile marker functionality.
 *
 * Self-contained: owns all tile marker UI. ClientSidebar knows nothing
 * about tile markers specifically — it just calls buildPanel().
 */
public class TileMarkerPlugin implements Plugin {

    // Sidebar theme colours — duplicated here so the plugin is self-contained
    private static final Color BG_PANEL   = new Color(22, 22, 26);
    private static final Color ACCENT     = new Color(255, 185, 0);
    private static final Color BORDER_COL = new Color(50, 50, 58);
    private static final Color TEXT_COL   = new Color(200, 200, 208);

    @Override public String getIcon() { return "⬡"; }
    @Override public String getName() { return "Tile Markers"; }

    @Override
    public void onLoad() {
        TileMarkerInput.install();
        System.out.println("[TileMarkerPlugin] loaded");
    }

    @Override
    public JPanel buildPanel() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        p.setBorder(new EmptyBorder(6, 8, 6, 8));

        // Enabled toggle
        p.add(buildToggleRow("Enabled",
                TileMarkerInput::isEnabled,
                TileMarkerInput::setEnabled));
        p.add(Box.createVerticalStrut(4));

        // Colour picker
        p.add(buildColourRow());
        p.add(Box.createVerticalStrut(4));

        // Clear button
        p.add(buildClearButton());
        p.add(Box.createVerticalStrut(4));

        // Hint label
        JLabel hint = new JLabel("Shift + right-click to place");
        hint.setForeground(new Color(120, 120, 130));
        hint.setFont(new Font("Monospaced", Font.PLAIN, 10));
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(hint);

        return p;
    }

    // -------------------------------------------------------------------------
    // Row builders
    // -------------------------------------------------------------------------

    private JPanel buildToggleRow(String label,
                                   java.util.function.BooleanSupplier getter,
                                   java.util.function.Consumer<Boolean> setter) {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel lbl = new JLabel(label);
        lbl.setForeground(TEXT_COL);
        lbl.setFont(new Font("Monospaced", Font.PLAIN, 10));

        JButton toggle = new JButton() {
            @Override protected void paintComponent(Graphics g) {
                boolean on = getter.getAsBoolean();
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(on ? new Color(60, 40, 0) : new Color(35, 35, 40));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 5, 5);
                g2.setColor(on ? ACCENT : BORDER_COL);
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 5, 5);
                g2.setColor(on ? ACCENT : new Color(120, 120, 130));
                g2.setFont(new Font("Monospaced", Font.BOLD, 9));
                FontMetrics fm = g2.getFontMetrics();
                String txt = on ? "ON" : "OFF";
                g2.drawString(txt,
                        (getWidth() - fm.stringWidth(txt)) / 2,
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

    private JPanel buildColourRow() {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel lbl = new JLabel("Marker colour");
        lbl.setForeground(TEXT_COL);
        lbl.setFont(new Font("Monospaced", Font.PLAIN, 10));

        JPanel swatch = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                Color c = TileMarkerInput.getCurrentColor();
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Color.GRAY);
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setColor(Color.LIGHT_GRAY);
                for (int i = 0; i < getWidth(); i += 4)
                    for (int j = 0; j < getHeight(); j += 4)
                        if ((i + j) % 8 == 0) g2.fillRect(i, j, 4, 4);
                g2.setColor(c);
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
                showColourDialog(swatch);
            }
        });

        row.add(lbl, BorderLayout.CENTER);
        row.add(swatch, BorderLayout.EAST);
        return row;
    }

    private JButton buildClearButton() {
        JButton b = new JButton("Clear all markers") {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getModel().isPressed()
                        ? new Color(80, 30, 0)
                        : getModel().isRollover()
                        ? new Color(60, 25, 0)
                        : new Color(35, 35, 40));
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
        b.addActionListener(ev -> {
            int r = JOptionPane.showConfirmDialog(b,
                    "Clear all tile markers?", "Confirm",
                    JOptionPane.YES_NO_OPTION);
            if (r == JOptionPane.YES_OPTION) TileMarkerStore.clear();
        });
        return b;
    }

    private void showColourDialog(JPanel swatch) {
        JDialog dlg = new JDialog((Window) null, "Marker Colour",
                Dialog.ModalityType.APPLICATION_MODAL);
        dlg.setSize(260, 200);
        dlg.setLocationRelativeTo(swatch);
        dlg.getContentPane().setBackground(BG_PANEL);
        dlg.getContentPane().setLayout(new BorderLayout(8, 8));

        Color cur = TileMarkerInput.getCurrentColor();
        int[] rgba = {cur.getRed(), cur.getGreen(), cur.getBlue(), cur.getAlpha()};
        String[] names = {"R", "G", "B", "A"};
        JSlider[] ss = new JSlider[4];

        JPanel preview = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                g.setColor(new Color(rgba[0], rgba[1], rgba[2], rgba[3]));
                g.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        preview.setPreferredSize(new Dimension(0, 24));

        JPanel sliders = new JPanel(new GridLayout(4, 2, 4, 4));
        sliders.setOpaque(false);
        sliders.setBorder(new EmptyBorder(10, 10, 4, 10));
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
            Color c = new Color(rgba[0], rgba[1], rgba[2], rgba[3]);
            TileMarkerInput.setCurrentColor(
                    new Color(c.getRed(), c.getGreen(), c.getBlue(), 180));
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