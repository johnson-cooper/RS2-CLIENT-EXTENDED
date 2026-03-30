package agent.plugins.fps;

import agent.*;
import agent.plugins.fps.FpsOverlay;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

public class FpsPlugin implements Plugin {

    @Override public String getIcon() { return "⏱"; }
    @Override public String getName() { return "FPS Counter"; }

    @Override
    public void onLoad() {
        System.out.println("[FpsPlugin] loaded");
    }

    @Override
    public JPanel buildPanel() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        p.setBorder(new EmptyBorder(6, 8, 6, 8));

        // Enabled toggle
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel lbl = new JLabel("Show FPS");
        lbl.setForeground(new Color(200, 200, 208));
        lbl.setFont(new Font("Monospaced", Font.PLAIN, 10));

        JButton toggle = new JButton() {
            @Override protected void paintComponent(Graphics g) {
                boolean on = FpsOverlay.isEnabled();
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                Color accent = new Color(255, 185, 0);
                Color border = new Color(50, 50, 58);
                g2.setColor(on ? new Color(60, 40, 0) : new Color(35, 35, 40));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 5, 5);
                g2.setColor(on ? accent : border);
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 5, 5);
                g2.setColor(on ? accent : new Color(120, 120, 130));
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
            FpsOverlay.setEnabled(!FpsOverlay.isEnabled());
            toggle.repaint();
        });

        row.add(lbl, BorderLayout.CENTER);
        row.add(toggle, BorderLayout.EAST);
        p.add(row);

        return p;
    }
}