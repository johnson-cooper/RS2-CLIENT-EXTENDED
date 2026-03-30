package agent.plugins.tilemarker;

import agent.*;

import java.awt.*;
import java.util.List;

public class TileMarkerOverlay {

    private static final int TILE = 128;
    private static final int VP_X1 = 8;
    private static final int VP_Y1 = 8;
    private static final int VP_X2 = 508;
    private static final int VP_Y2 = 330;

    public static void draw(Graphics g) {
        if (!(g instanceof Graphics2D)) return;

        // Gate everything on being in-game — checked before any other work
        if (!TileProjector.isReady()) return;
        TileProjector.updateCamera();
        if (!TileProjector.isInGame()) return;
        if (!TileMarkerInput.isEnabled()) return;

        List<TileMarker> all = TileMarkerStore.getAll();
        if (all.isEmpty()) return;

        Graphics2D g2d = (Graphics2D) g;
        Color  savedColor  = g2d.getColor();
        Stroke savedStroke = g2d.getStroke();
        Font   savedFont   = g2d.getFont();

        for (TileMarker m : all) {
            int wx = m.tileX * TILE;
            int wz = m.tileY * TILE;

            int[] c0 = TileProjector.project(wx,        wz);
            int[] c1 = TileProjector.project(wx + TILE, wz);
            int[] c2 = TileProjector.project(wx + TILE, wz + TILE);
            int[] c3 = TileProjector.project(wx,        wz + TILE);

            if (c0[0] < 0 || c1[0] < 0 || c2[0] < 0 || c3[0] < 0) continue;
            if (anyWild(c0, c1, c2, c3)) continue;
            if (!allInViewport(c0, c1, c2, c3)) continue;

            int[] xp = {c0[0], c1[0], c2[0], c3[0]};
            int[] yp = {c0[1], c1[1], c2[1], c3[1]};

            g2d.setColor(new Color(
                    m.color.getRed(), m.color.getGreen(), m.color.getBlue(), 60));
            g2d.fillPolygon(xp, yp, 4);

            g2d.setColor(m.color);
            g2d.setStroke(new BasicStroke(1.5f));
            g2d.drawPolygon(xp, yp, 4);

            if (m.label != null && !m.label.isEmpty()) {
                int[] ctr = TileProjector.project(wx + TILE / 2, wz + TILE / 2);
                if (ctr[0] >= VP_X1 && ctr[0] <= VP_X2
                        && ctr[1] >= VP_Y1 && ctr[1] <= VP_Y2) {
                    g2d.setFont(new Font("Monospaced", Font.BOLD, 11));
                    FontMetrics fm = g2d.getFontMetrics();
                    int tx = ctr[0] - fm.stringWidth(m.label) / 2;
                    int ty = ctr[1] + fm.getAscent() / 2;
                    g2d.setColor(Color.BLACK);
                    g2d.drawString(m.label, tx + 1, ty + 1);
                    g2d.setColor(m.color);
                    g2d.drawString(m.label, tx, ty);
                }
            }
        }

        g2d.setColor(savedColor);
        g2d.setStroke(savedStroke);
        g2d.setFont(savedFont);
    }

    private static boolean allInViewport(int[]... corners) {
        for (int[] c : corners) {
            if (c[0] < VP_X1 || c[0] > VP_X2) return false;
            if (c[1] < VP_Y1 || c[1] > VP_Y2) return false;
        }
        return true;
    }

    private static boolean anyWild(int[]... corners) {
        for (int[] c : corners) {
            if (Math.abs(c[0]) > 2000 || Math.abs(c[1]) > 2000) return true;
        }
        return false;
    }
}