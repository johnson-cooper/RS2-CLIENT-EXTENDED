package agent;

import java.awt.*;
import java.awt.geom.AffineTransform;

/**
 * Runtime helper called from bytecode injected into the RS2 framebuffer class.
 * Must be on Boot-Class-Path in MANIFEST.MF.
 */
public final class ScaleHelper {

    private ScaleHelper() {}

    private static final ThreadLocal<Graphics2D> currentGraphics = new ThreadLocal<>();

    /** Called at the top of the game's render method with its Graphics arg. */
    public static void registerGraphics(Graphics g) {
        if (g instanceof Graphics2D) {
            currentGraphics.set((Graphics2D) g);
        }
    }

    /**
     * Called just before Graphics.drawImage().
     * Reads the live scale from ClientConfig (updated on every window resize)
     * and sets an absolute AffineTransform — never compounds.
     */
    public static void applyScale() {
        Graphics2D g2d = currentGraphics.get();

        if (g2d == null) {
            g2d = graphicsFromFrame();
            if (g2d != null) currentGraphics.set(g2d);
        }

        if (g2d == null) return;

        try {
            // ClientConfig.getScaleX/Y() = innerWidth / 765.0 etc.
            // Updated live whenever the user resizes the window.
            AffineTransform at = AffineTransform.getScaleInstance(
                    ClientConfig.getScaleX(),
                    ClientConfig.getScaleY()
            );
            g2d.setTransform(at);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    /** Clear the stored Graphics after each frame. */
    public static void clearGraphics() {
        Graphics2D g = currentGraphics.get();
        if (g != null) {
            try { g.setTransform(new AffineTransform()); } catch (Throwable ignored) {}
        }
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