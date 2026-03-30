package agent.plugins.fps;

import agent.*;

import java.awt.*;

/**
 * Draws the FPS counter in the top-left of the game viewport.
 * Called from Hooks.drawOverlayDirect() each frame.
 */
public class FpsOverlay {

    private static volatile boolean enabled = true;

    public static boolean isEnabled()           { return enabled; }
    public static void    setEnabled(boolean v) { enabled = v; }

    public static void draw(Graphics2D g, int fps) {
        if (!enabled) return;
        if (!TileProjector.isInGame()) return;

        String text = "FPS: " + fps;

        g.setFont(new Font("Monospaced", Font.BOLD, 11));
        FontMetrics fm = g.getFontMetrics();

        int x = 6;
        int y = 14;
        int pad = 3;

        // Dark background for readability
        g.setColor(new Color(0, 0, 0, 140));
        g.fillRoundRect(x - pad, y - fm.getAscent() - pad,
                fm.stringWidth(text) + pad * 2,
                fm.getAscent() + fm.getDescent() + pad * 2,
                4, 4);

        // White text
        g.setColor(Color.WHITE);
        g.drawString(text, x, y);
    }
}