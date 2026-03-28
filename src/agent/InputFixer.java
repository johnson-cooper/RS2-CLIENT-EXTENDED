package agent;

import java.awt.Component;
import java.awt.event.MouseEvent;
import java.lang.reflect.Field;

public class InputFixer {

    // Cached reflective access to MouseEvent's x and y fields
    private static Field fieldX;
    private static Field fieldY;

    static {
        try {
            // x and y are defined on java.awt.event.MouseEvent's parent: ComponentEvent -> AWTEvent
            // but the actual fields live in MouseEvent itself via inherited path.
            // In practice they're declared in java.awt.event.MouseEvent as 'x' and 'y'.
            fieldX = MouseEvent.class.getDeclaredField("x");
            fieldY = MouseEvent.class.getDeclaredField("y");
            fieldX.setAccessible(true);
            fieldY.setAccessible(true);
            System.out.println("[InputFixer] reflection ready");
        } catch (Throwable t) {
            // Java 9+ may need --add-opens java.desktop/java.awt.event=ALL-UNNAMED
            System.out.println("[InputFixer] reflection failed: " + t);
            t.printStackTrace();
        }
    }

    /**
     * Called from Bootstrap's AWTEventListener for every mouse event.
     *
     * Converts the screen-space coordinate (relative to the stretched window)
     * back to game-space (765x503) by dividing by the current scale factor.
     *
     * This modifies the event IN PLACE so the game receives the correct
     * coordinate without needing any other changes.
     */
    public static void handle(MouseEvent e) {
        if (fieldX == null || fieldY == null) return;

        Component c = e.getComponent();
        if (c == null) return;

        int w = c.getWidth();
        int h = c.getHeight();
        if (w <= 0 || h <= 0) return;

        // Scale factor = how much bigger the window is vs the native canvas.
        // We divide by it to map window coords → game coords.
        double scaleX = (double) ClientConfig.BASE_WIDTH  / w;
        double scaleY = (double) ClientConfig.BASE_HEIGHT / h;

        int gameX = (int) (e.getX() * scaleX);
        int gameY = (int) (e.getY() * scaleY);

        // Clamp to game bounds so edge clicks don't go out of range
        gameX = Math.max(0, Math.min(gameX, ClientConfig.BASE_WIDTH  - 1));
        gameY = Math.max(0, Math.min(gameY, ClientConfig.BASE_HEIGHT - 1));

        try {
            fieldX.set(e, gameX);
            fieldY.set(e, gameY);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}