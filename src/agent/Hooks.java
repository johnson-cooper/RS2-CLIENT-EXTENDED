package agent;

import agent.plugins.tilemarker.*;
import agent.plugins.fps.FpsOverlay;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.util.concurrent.atomic.AtomicLong;

public final class Hooks {

    private static final AtomicLong frameCounter = new AtomicLong();
    private static volatile long lastSecond = System.nanoTime();
    private static volatile int fps;

    // Dimensions of the game 3D viewport framebuffer (this.x = new at(512, 334, this))
    private static final int GAME_FB_WIDTH  = 512;
    private static final int GAME_FB_HEIGHT = 334;

    private Hooks() {}

    public static void onFrame() {
        long now = System.nanoTime();
        long count = frameCounter.incrementAndGet();
        ProjectionCache.clearFrame();
        if (now - lastSecond >= 1_000_000_000L) {
            fps = (int) count;
            lastSecond = now;
            frameCounter.set(0);
        }
    }

    /**
     * Called from at.a(int, Graphics, int) before drawImage.
     * We pass the at instance so we can check its dimensions.
     * Only draw the overlay when the game 3D framebuffer (512x334) is being blitted.
     */
    public static void drawOverlayDirect(Object atInstance) {
        onFrame();

        // Only draw when the game viewport framebuffer is being blitted
        if (!isGameFramebuffer(atInstance)) return;

        if (!TileProjector.isReady()) return;
        TileProjector.updateCamera();
        if (!TileProjector.isInGame()) return;

        Graphics g = ScaleHelper.getGraphics();
        if (g == null) return;
        Graphics2D g2d = (Graphics2D) g;
        FpsOverlay.draw(g2d, fps);
        TileMarkerOverlay.draw(g2d);
    }

    // Keep old signature for compatibility
    public static void drawOverlayDirect() {
        drawOverlayDirect(null);
    }

    private static boolean isGameFramebuffer(Object atInstance) {
        if (atInstance == null) return true; // fallback: assume game
        try {
            // at has public final int[] cJ — its length = width * height
            // Game framebuffer: 512 * 334 = 171008
            java.lang.reflect.Field cj = atInstance.getClass().getDeclaredField("cJ");
            cj.setAccessible(true);
            int[] pixels = (int[]) cj.get(atInstance);
            return pixels != null && pixels.length == GAME_FB_WIDTH * GAME_FB_HEIGHT;
        } catch (Exception e) {
            return true; // if reflection fails, assume game framebuffer
        }
    }

    public static void onPaint(Graphics g) {
        // no-op — kept for compatibility if FrameBufferScaleTransformer still hooks it
    }

    public static void drawOverlay(Graphics g) {
        if (!(g instanceof Graphics2D)) return;
        if (!TileProjector.isReady()) return;
        if (!TileProjector.isInGame()) return;
        Graphics2D g2 = (Graphics2D) g;
        AffineTransform saved = g2.getTransform();
        Font savedFont = g2.getFont();
        Color savedColor = g2.getColor();
        TileMarkerOverlay.draw(g2);
        g2.setFont(new Font("Monospaced", Font.BOLD, 10));
        g2.setColor(Color.WHITE);
        g2.drawString("FPS: " + fps, 10, 15);
        g2.setTransform(saved);
        g2.setFont(savedFont);
        g2.setColor(savedColor);
    }

    public static class Tile {
        public final int x, y, plane;
        public Tile(int x, int y, int plane) { this.x = x; this.y = y; this.plane = plane; }
    }
}