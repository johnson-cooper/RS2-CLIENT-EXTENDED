package agent.plugins.tilemarker;

import agent.*;

import java.awt.Color;

/**
 * Represents a single marked tile.
 * Coordinates are in RS2 tile-space (not pixels).
 */
public class TileMarker {

    public int tileX;   // game tile X
    public int tileY;   // game tile Y
    public int plane;   // 0–3 (floor level)
    public Color color;
    public String label; // optional, may be null

    public TileMarker(int tileX, int tileY, int plane, Color color, String label) {
        this.tileX = tileX;
        this.tileY = tileY;
        this.plane = plane;
        this.color = color;
        this.label = label;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof TileMarker)) return false;
        TileMarker m = (TileMarker) o;
        return tileX == m.tileX && tileY == m.tileY && plane == m.plane;
    }

    @Override
    public int hashCode() {
        return 31 * (31 * tileX + tileY) + plane;
    }
}