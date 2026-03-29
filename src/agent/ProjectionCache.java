package agent;

import java.util.concurrent.atomic.AtomicLong;

public final class ProjectionCache {

    private ProjectionCache() {}

    public static final int OFF_SCREEN = Integer.MIN_VALUE;

    private static final int   MAX_POINTS = 8192;
    private static final int[] pointsX    = new int[MAX_POINTS];
    private static final int[] pointsY    = new int[MAX_POINTS];
    private static volatile int pointCount = 0;
    private static final AtomicLong totalFrames = new AtomicLong();

    public static void recordPair(int screenX, int screenY, int seq) {
        int idx = seq & (MAX_POINTS - 1);
        pointsX[idx] = screenX;
        pointsY[idx] = screenY;
        if (pointCount < MAX_POINTS) pointCount++;
    }

    // kept for VertexArrayReader compatibility
    public static void recordVertices(int[] bB, int[] bC, int count) {}

    public static void record(int v, int x, int y, boolean isX) {}
    public static int getScreenX(int x, int y) { return OFF_SCREEN; }
    public static int getScreenY(int x, int y) { return OFF_SCREEN; }
    public static boolean isVisible(int x, int y) { return false; }

    public static void clearFrame() {
        pointCount = 0;
        totalFrames.incrementAndGet();
    }

    public static int[] nearestPoint(int approxX, int approxY) {
        int count = pointCount;
        if (count == 0) return new int[]{OFF_SCREEN, OFF_SCREEN};
        int bestX = OFF_SCREEN, bestY = OFF_SCREEN, bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            int dx = pointsX[i] - approxX;
            int dy = pointsY[i] - approxY;
            int dist = dx*dx + dy*dy;
            if (dist < bestDist) { bestDist = dist; bestX = pointsX[i]; bestY = pointsY[i]; }
        }
        return new int[]{bestX, bestY};
    }

    public static void debugDump() {
        int count = pointCount;
        System.out.println("[ProjectionCache] pointCount=" + count
                + " totalFrames=" + totalFrames.get());
        for (int i = 0; i < Math.min(count, 8); i++) {
            System.out.println("  [" + i + "] (" + pointsX[i] + "," + pointsY[i] + ")");
        }
    }
}