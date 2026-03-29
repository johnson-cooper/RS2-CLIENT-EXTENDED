package agent;

import java.awt.*;
import java.awt.geom.AffineTransform;

public final class ScaleHelper {

    private ScaleHelper() {}

    private static final ThreadLocal<Graphics2D> currentGraphics = new ThreadLocal<>();

    public static void registerGraphics(Graphics g) {
        if (g instanceof Graphics2D) {
            currentGraphics.set((Graphics2D) g);
        }
    }

    public static void applyScale() {
        Graphics2D g2d = currentGraphics.get();
        if (g2d == null) {
            g2d = graphicsFromFrame();
            if (g2d != null) currentGraphics.set(g2d);
        }
        if (g2d == null) return;
        try {
            // Use ClientConfig which is kept current by ClientSidebar.layoutFrame
            // on every resize event — no lag, no debounce needed.
            g2d.setTransform(AffineTransform.getScaleInstance(
                    ClientConfig.getScaleX(),
                    ClientConfig.getScaleY()
            ));
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    public static Graphics2D getGraphics() {
        return currentGraphics.get();
    }

    public static void clearGraphics() {
        // Only release our reference — never reset the transform.
        // The game manages its own Graphics state after drawImage.
        currentGraphics.remove();
    }

    private static Graphics2D graphicsFromFrame() {
        try {
            for (Frame f : Frame.getFrames()) {
                if (f != null && f.isVisible()) {
                    Graphics g = f.getGraphics();
                    if (g instanceof Graphics2D) return (Graphics2D) g;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }
}