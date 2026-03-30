package agent;

import java.awt.*;
import java.awt.event.MouseWheelEvent;

/**
 * Mouse wheel zoom via bytecode-patched focal length.
 * ZoomTransformer replaces (x << 9) with (x * getFocal()) in aN and Z.
 */
public class ZoomController {

    private static final int FOCAL_DEFAULT = 512;
    private static final int FOCAL_MIN     = 256;  // wider zoom out
    private static final int FOCAL_MAX     = 1024; // wider zoom in
    private static final int FOCAL_STEP    = 32;

    private static volatile int focalLength = FOCAL_DEFAULT;

    public static int getFocal() {
        return focalLength;
    }

    /**
     * Scale a world-space coordinate by the current focal ratio.
     * Called from patched Z.c() bytecode before storing bW/bX.
     * Keeps UV gradients consistent with the changed projection scale.
     */
    public static int scaledWorld(int v) {
        return v * focalLength >> 9; // v * focal / 512
    }

    public static void handleWheel(MouseWheelEvent e) {
        if (!TileProjector.isInGame()) return;
        if (isAgentComponent(e.getComponent())) return;

        int newFocal = focalLength - e.getWheelRotation() * FOCAL_STEP;
        focalLength = Math.max(FOCAL_MIN, Math.min(FOCAL_MAX, newFocal));
        e.consume();
    }

    public static void install() {
        System.out.println("[ZoomController] installed, focal=" + FOCAL_DEFAULT);
    }

    public static void init(Object client, Class<?> clientClass) {
        System.out.println("[ZoomController] ready — bytecode focal length zoom");
    }


    // texturedTri() removed - AjARedirectPatcher now inserts
    // scaledWorld() calls inline before the aJ.a() invocation
    // (avoids referencing the obfuscated aJ class from agent code)
    private static boolean isAgentComponent(Component c) {
        while (c != null) {
            if (c.getClass().getName().startsWith("agent.")) return true;
            if (c instanceof javax.swing.JDialog) return true;
            c = c.getParent();
        }
        return false;
    }
}