package agent.plugins.tilemarker;

import agent.*;

import java.awt.Color;
import java.io.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory store for tile markers with simple JSON persistence.
 * Saved to: <user.home>/.rs2client/markers.json
 */
public class TileMarkerStore {

    private static final List<TileMarker> markers = new CopyOnWriteArrayList<>();
    private static final File SAVE_FILE;

    static {
        File dir = new File(System.getProperty("user.home"), ".rs2client");
        dir.mkdirs();
        SAVE_FILE = new File(dir, "markers.json");
        load();
    }

    public static List<TileMarker> getAll() {
        return Collections.unmodifiableList(markers);
    }

    /**
     * Toggle: if a marker exists at this tile, remove it. Otherwise add it.
     * Returns true if marker was ADDED, false if it was REMOVED.
     */
    public static boolean toggle(int tileX, int tileY, int plane, Color color, String label) {
        for (TileMarker m : markers) {
            if (m.tileX == tileX && m.tileY == tileY && m.plane == plane) {
                markers.remove(m);
                save();
                return false;
            }
        }
        markers.add(new TileMarker(tileX, tileY, plane, color, label));
        save();
        return true;
    }

    public static void clear() {
        markers.clear();
        save();
    }

    // -------------------------------------------------------------------------
    // Minimal hand-rolled JSON — no external dependency needed
    // -------------------------------------------------------------------------

    private static void save() {
        try (PrintWriter pw = new PrintWriter(new FileWriter(SAVE_FILE))) {
            pw.println("[");
            List<TileMarker> snap = new ArrayList<>(markers);
            for (int i = 0; i < snap.size(); i++) {
                TileMarker m = snap.get(i);
                pw.printf(
                    "  {\"x\":%d,\"y\":%d,\"plane\":%d,\"r\":%d,\"g\":%d,\"b\":%d,\"a\":%d,\"label\":%s}%s%n",
                    m.tileX, m.tileY, m.plane,
                    m.color.getRed(), m.color.getGreen(), m.color.getBlue(), m.color.getAlpha(),
                    m.label == null ? "null" : "\"" + m.label.replace("\"", "\\\"") + "\"",
                    i < snap.size() - 1 ? "," : ""
                );
            }
            pw.println("]");
        } catch (IOException e) {
            System.out.println("[Markers] save failed: " + e);
        }
    }

    private static void load() {
        if (!SAVE_FILE.exists()) return;
        try (BufferedReader br = new BufferedReader(new FileReader(SAVE_FILE))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            String json = sb.toString().trim();

            // Minimal parser: pull each {...} object out and parse fields
            int i = 0;
            while ((i = json.indexOf('{', i)) != -1) {
                int end = json.indexOf('}', i);
                if (end == -1) break;
                String obj = json.substring(i + 1, end);
                i = end + 1;

                int x     = intField(obj, "x");
                int y     = intField(obj, "y");
                int plane = intField(obj, "plane");
                int r     = intField(obj, "r");
                int g     = intField(obj, "g");
                int b     = intField(obj, "b");
                int a     = intField(obj, "a");
                String label = stringField(obj, "label");

                markers.add(new TileMarker(x, y, plane,
                        new Color(r, g, b, a), label));
            }
            System.out.println("[Markers] loaded " + markers.size() + " markers");
        } catch (Exception e) {
            System.out.println("[Markers] load failed: " + e);
        }
    }

    private static int intField(String obj, String key) {
        String search = "\"" + key + "\":";
        int idx = obj.indexOf(search);
        if (idx == -1) return 0;
        idx += search.length();
        int end = idx;
        while (end < obj.length() && (Character.isDigit(obj.charAt(end)) || obj.charAt(end) == '-'))
            end++;
        try { return Integer.parseInt(obj.substring(idx, end)); }
        catch (NumberFormatException e) { return 0; }
    }

    private static String stringField(String obj, String key) {
        String search = "\"" + key + "\":";
        int idx = obj.indexOf(search);
        if (idx == -1) return null;
        idx += search.length();
        if (obj.charAt(idx) == 'n') return null; // null
        if (obj.charAt(idx) == '"') {
            int end = obj.indexOf('"', idx + 1);
            return end == -1 ? null : obj.substring(idx + 1, end);
        }
        return null;
    }
}