package agent;

import java.awt.*;
import java.awt.event.MouseEvent;

public class TileMarkerInput {

    private static Color currentColor = new Color(0, 255, 255, 180);
    private static volatile boolean enabled = true;
    private static final int TILE_SIZE = 128;

    public static void install() {
        Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
            if (!(event instanceof MouseEvent)) return;
            MouseEvent e = (MouseEvent) event;
            if (e.getID()     != MouseEvent.MOUSE_PRESSED) return;
            if (e.getButton() != MouseEvent.BUTTON3)       return;
            if (!e.isShiftDown())                          return;
            if (!enabled)                                  return;

            Component c = e.getComponent();
            if (c == null) return;

            // InputFixer has already remapped e.getX()/e.getY() to game-pixel space
            int gameX = Math.max(0, Math.min(e.getX(), ClientConfig.BASE_WIDTH  - 1));
            int gameY = Math.max(0, Math.min(e.getY(), ClientConfig.BASE_HEIGHT - 1));

            int[] tile = screenToWorldTile(gameX, gameY);

            boolean added = TileMarkerStore.toggle(tile[0], tile[1], 0, currentColor, null);
            System.out.println("[TileMarker] " + (added ? "added" : "removed")
                    + " worldTile=(" + tile[0] + "," + tile[1] + ")"
                    + " click=(" + gameX + "," + gameY + ")");

            e.consume();
        }, AWTEvent.MOUSE_EVENT_MASK);
        System.out.println("[TileMarkerInput] installed");
    }

    private static int[] screenToWorldTile(int screenX, int screenY) {
        int[] camTile = TileProjector.getCameraTile();
        int camTileX = camTile[0];
        int camTileZ = camTile[1];

        int bestTileX = camTileX;
        int bestTileZ = camTileZ;
        int bestDist = Integer.MAX_VALUE;

        for (int dx = -15; dx <= 15; dx++) {
            for (int dz = -15; dz <= 15; dz++) {
                int tx = camTileX + dx;
                int tz = camTileZ + dz;
                int worldX = tx * TILE_SIZE + TILE_SIZE / 2;
                int worldZ = tz * TILE_SIZE + TILE_SIZE / 2;
                int[] sc = TileProjector.project(worldX, worldZ);
                if (sc[0] < 0 || sc[1] < 0) continue;
                int ddx = sc[0] - screenX;
                int ddz = sc[1] - screenY;
                int dist = ddx * ddx + ddz * ddz;
                if (dist < bestDist) {
                    bestDist = dist;
                    bestTileX = tx;
                    bestTileZ = tz;
                }
            }
        }
        return new int[]{bestTileX, bestTileZ};
    }

    public static boolean isEnabled()              { return enabled; }
    public static void    setEnabled(boolean on)   { enabled = on; }
    public static Color   getCurrentColor()        { return currentColor; }
    public static void    setCurrentColor(Color c) { currentColor = c; }
}