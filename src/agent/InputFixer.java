package agent;

import java.awt.Component;
import java.awt.Window;
import java.awt.event.MouseEvent;
import java.lang.reflect.Field;

public class InputFixer {

    private static Field fieldX;
    private static Field fieldY;

    static {
        try {
            fieldX = MouseEvent.class.getDeclaredField("x");
            fieldY = MouseEvent.class.getDeclaredField("y");
            fieldX.setAccessible(true);
            fieldY.setAccessible(true);
            System.out.println("[InputFixer] reflection ready");
        } catch (Throwable t) {
            System.out.println("[InputFixer] reflection failed: " + t);
        }
    }

    public static void handle(MouseEvent e) {
        if (fieldX == null || fieldY == null) return;

        Component c = e.getComponent();
        if (c == null) return;

        // Never remap inside agent-owned UI or standard Swing dialogs
        if (isAgentOrSwingComponent(c)) return;

        int w = c.getWidth();
        int h = c.getHeight();
        if (w <= 0 || h <= 0) return;

        // Skip if component is already at native game resolution
        if (w == ClientConfig.BASE_WIDTH && h == ClientConfig.BASE_HEIGHT) return;

        double scaleX = (double) ClientConfig.BASE_WIDTH  / w;
        double scaleY = (double) ClientConfig.BASE_HEIGHT / h;

        int gameX = Math.max(0, Math.min((int)(e.getX() * scaleX), ClientConfig.BASE_WIDTH  - 1));
        int gameY = Math.max(0, Math.min((int)(e.getY() * scaleY), ClientConfig.BASE_HEIGHT - 1));

        try {
            fieldX.set(e, gameX);
            fieldY.set(e, gameY);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private static boolean isAgentOrSwingComponent(Component c) {
        Component cur = c;
        while (cur != null) {
            String pkg = cur.getClass().getName();
            // Our sidebar / dialogs
            if (pkg.startsWith("agent.")) return true;
            // Any Swing dialog (color chooser lives here)
            if (cur instanceof javax.swing.JDialog) return true;
            if (cur instanceof javax.swing.JOptionPane) return true;
            cur = cur.getParent();
        }
        return false;
    }
}