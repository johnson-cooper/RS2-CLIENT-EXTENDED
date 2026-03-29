package agent;

import java.lang.reflect.Field;

public class TileProjector {

    private static volatile int camX, camY, camZ;
    private static volatile int camYaw, camPitch, camPlane;
    private static volatile int vpX, vpY;
    private static volatile boolean camReady = false;
    private static volatile boolean ready    = false;

    private static int[] sinTable;
    private static int[] cosTable;

    private static Field fCM, fCN, fCO, fCP, fCQ, fDJ;
    private static Field fMH, fMI, fDw, fDx;
    private static Field fHeightmap;
    private static Field fFlags;
    private static Object clientInstance;

    private static final int VP_X1 = 8;
    private static final int VP_Y1 = 8;
    private static final int VP_X2 = 508;
    private static final int VP_Y2 = 330;

    // Track transitions so we can log when isInGame flips
    private static volatile boolean wasInGame = false;

    public static void init(Object client, Class<?> clientClass,
                            Class<?> aJClass, Class<?> zClass) {
        try {
            fCM = findField(clientClass, "cM", int.class);
            fCN = findField(clientClass, "cN", int.class);
            fCO = findField(clientClass, "cO", int.class);
            fCP = findField(clientClass, "cP", int.class);
            fCQ = findField(clientClass, "cQ", int.class);
            fDJ = findField(clientClass, "dj", int.class);
            fMH = findField(aJClass,     "mh", int.class);
            fMI = findField(aJClass,     "mi", int.class);
            fDw = findField(aJClass,     "dw", int[].class);
            fDx = findField(aJClass,     "dx", int[].class);

            fHeightmap = findFieldByNameAndType(clientClass, "d", int[][][].class);
            if (fHeightmap != null)
                System.out.println("[TileProjector] heightmap='" + fHeightmap.getName() + "'");

            fFlags = findFieldByNameAndType(clientClass, "b", byte[][][].class);
            if (fFlags != null)
                System.out.println("[TileProjector] flags='" + fFlags.getName() + "'");

            clientInstance = client;
            sinTable = (int[]) fDw.get(null);
            cosTable = (int[]) fDx.get(null);

            ready = true;
            System.out.println("[TileProjector] ready — sin/cos len=" + sinTable.length);
        } catch (Exception e) {
            System.out.println("[TileProjector] init failed: " + e);
            e.printStackTrace();
        }
    }

    private static Field findField(Class<?> cls, String name, Class<?> type)
            throws NoSuchFieldException {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getName().equals(name) && f.getType() == type) {
                    f.setAccessible(true);
                    return f;
                }
            }
        }
        throw new NoSuchFieldException(name + ":" + type.getSimpleName() + " in " + cls);
    }

    private static Field findFieldByNameAndType(Class<?> cls, String name, Class<?> type) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getName().equals(name) && f.getType() == type) {
                    f.setAccessible(true);
                    return f;
                }
            }
        }
        return null;
    }

    public static void updateCamera() {
        if (!ready) return;
        try {
            camX     = (int) fCM.get(clientInstance);
            camY     = (int) fCN.get(clientInstance);
            camZ     = (int) fCO.get(clientInstance);
            camYaw   = (int) fCQ.get(clientInstance);
            camPitch = (int) fCP.get(clientInstance);
            camPlane = (int) fDJ.get(clientInstance);
            vpX      = (int) fMH.get(null);
            vpY      = (int) fMI.get(null);
            camReady = true;



            // Log every transition so we can see exactly when isInGame flips
            boolean nowInGame = isInGame();
            if (nowInGame != wasInGame) {
                wasInGame = nowInGame;
                System.out.println("[TileProjector] isInGame -> " + nowInGame
                        + " cam=(" + camX + "," + camY + "," + camZ + ")");
            }
        } catch (Exception ignored) {}
    }

    public static boolean isInGame() {
        if (!camReady) return false;
        // ef stays at 2 after logout in this client so we can't use it.
        // Use camera position bounds instead — in-game camX/camZ are always
        // within the scene bounds 128-13056, and camY is large negative.
        return camX >= 128 && camX <= 13056
            && camZ >= 128 && camZ <= 13056
            && camY < -100;
    }

    private static int terrainHeight(int worldX, int worldZ) {
        if (fHeightmap == null) return camY;
        try {
            int[][][] d = (int[][][]) fHeightmap.get(clientInstance);
            if (d == null || d.length == 0) return camY;

            int plane = Math.max(0, Math.min(camPlane, d.length - 1));

            if (fFlags != null && plane < 3) {
                try {
                    byte[][][] b = (byte[][][]) fFlags.get(clientInstance);
                    int tx0 = worldX >> 7, tz0 = worldZ >> 7;
                    if (b != null && b.length > 1
                            && tx0 >= 0 && tx0 < b[1].length
                            && tz0 >= 0 && tz0 < b[1][tx0].length
                            && (b[1][tx0][tz0] & 2) == 2) {
                        plane = Math.min(plane + 1, d.length - 1);
                    }
                } catch (Exception ignored) {}
            }

            int[][] dp = d[plane];
            if (dp == null) return camY;

            int tx = worldX >> 7;
            int tz = worldZ >> 7;
            if (tx < 0 || tz < 0 || tx + 1 >= dp.length) return camY;
            if (dp[tx] == null || dp[tx + 1] == null) return camY;
            if (tz + 1 >= dp[tx].length) return camY;

            int fx = worldX & 127;
            int fz = worldZ & 127;

            int h00 = dp[tx][tz];
            int h10 = dp[tx + 1][tz];
            int h01 = dp[tx][tz + 1];
            int h11 = dp[tx + 1][tz + 1];

            int h0 = (h00 * (128 - fx) + h10 * fx) >> 7;
            int h1 = (h01 * (128 - fx) + h11 * fx) >> 7;
            return (h0 * (128 - fz) + h1 * fz) >> 7;

        } catch (Exception e) {
            return camY;
        }
    }

    public static int[] project(int worldX, int worldZ) {
        if (!camReady || !isInGame()) return new int[]{-1, -1};

        int[] sin = sinTable;
        int[] cos = cosTable;
        if (sin == null || cos == null) return new int[]{-1, -1};

        int terrainH = terrainHeight(worldX, worldZ);

        int dx = worldX - camX;
        int dy = terrainH - camY;
        int dz = worldZ - camZ;

        int sinYaw   = sin[camYaw   & 2047];
        int cosYaw   = cos[camYaw   & 2047];
        int sinPitch = sin[camPitch & 2047];
        int cosPitch = cos[camPitch & 2047];

        int x2 = (dz * sinYaw  + dx * cosYaw)  >> 16;
        int z2 = (dz * cosYaw  - dx * sinYaw)  >> 16;
        int y2 = (dy * cosPitch - z2 * sinPitch) >> 16;
        int z3 = (dy * sinPitch + z2 * cosPitch) >> 16;

        if (z3 < 50) return new int[]{-1, -1};

        int focal = agent.ZoomController.getFocal();
        return new int[]{
            vpX + (x2 * focal) / z3,
            vpY + (y2 * focal) / z3
        };
    }

    public static boolean inViewport(int sx, int sy) {
        return sx >= VP_X1 && sx <= VP_X2 && sy >= VP_Y1 && sy <= VP_Y2;
    }

    public static boolean isReady() { return ready; }

    public static int[] getCameraTile() {
        return new int[]{camX / 128, camZ / 128};
    }

    public static void debugState() {
        System.out.println("[TileProjector] cam=(" + camX + "," + camY + "," + camZ + ")"
                + " yaw(cQ)=" + camYaw + " pitch(cP)=" + camPitch
                + " plane=" + camPlane + " vp=(" + vpX + "," + vpY + ")"
                + " isInGame=" + isInGame());
        int testX = (camX / 128) * 128 + 64;
        int testZ = (camZ / 128) * 128 + 64;
        int h = terrainHeight(testX, testZ);
        int[] sc = project(testX, testZ);
        System.out.println("[TileProjector] camTile terrain=" + h
                + " camY=" + camY + " dy=" + (h - camY)
                + " -> screen=" + (sc[0] < 0 ? "BEHIND_CAM" : "(" + sc[0] + "," + sc[1] + ")"));
    }
}