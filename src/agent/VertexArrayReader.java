package agent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reads Z.bB (screen X) and Z.bC (screen Y) vertex arrays via reflection.
 *
 * Self-bootstrapping: starts a background thread that polls for the Z class
 * and its fields every 500ms until found. Does not rely on onFrame() being
 * called, and does not rely on Z being loaded at agent startup.
 *
 * Once fields are found, switches to per-frame reads triggered by onFrame().
 */
public class VertexArrayReader {

    private static volatile Field  fieldBB;
    private static volatile Field  fieldBC;
    private static volatile Field  fieldIX;
    private static volatile Object zInstance; // null if fields are static
    private static volatile boolean fieldsStatic;
    private static volatile boolean ready = false;

    private static final AtomicLong  frameCount  = new AtomicLong();
    private static final AtomicBoolean searching  = new AtomicBoolean(false);

    public static void install(Instrumentation inst) {
        System.out.println("[VAR] install called");

        // Try immediately in case Z is already loaded
        if (tryFind(inst)) return;

        // Otherwise poll in background
        if (searching.compareAndSet(false, true)) {
            Thread t = new Thread(() -> {
                for (int attempt = 0; attempt < 60; attempt++) {
                    try { Thread.sleep(500); } catch (InterruptedException e) { return; }
                    if (tryFind(inst)) return;
                }
                System.out.println("[VAR] gave up after 30s — Z class not found");
            }, "VAR-finder");
            t.setDaemon(true);
            t.start();
            System.out.println("[VAR] background finder started");
        }
    }

    private static boolean tryFind(Instrumentation inst) {
        Class<?> zClass = null;
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (c.getName().equals("Z")) { zClass = c; break; }
        }
        if (zClass == null) return false;

        System.out.println("[VAR] found Z class, scanning fields...");

        // List all int and int[] fields for diagnostics
        for (Field f : zClass.getDeclaredFields()) {
            if (f.getType() == int[].class || f.getType() == int.class) {
                System.out.println("[VAR]   " + (Modifier.isStatic(f.getModifiers()) ? "static " : "")
                        + f.getType().getSimpleName() + " " + f.getName());
            }
        }

        try {
            Field bb = zClass.getDeclaredField("bB");
            Field bc = zClass.getDeclaredField("bC");
            Field ix = zClass.getDeclaredField("iX");
            bb.setAccessible(true);
            bc.setAccessible(true);
            ix.setAccessible(true);

            fieldsStatic = Modifier.isStatic(bb.getModifiers());
            System.out.println("[VAR] Z.bB/bC/iX found — " + (fieldsStatic ? "STATIC" : "INSTANCE"));

            fieldBB = bb;
            fieldBC = bc;
            fieldIX = ix;

            if (fieldsStatic) {
                ready = true;
                System.out.println("[VAR] ready (static)");
                readAndLog(); // immediate sample
            } else {
                // Find instance — search all static fields of all classes
                findInstance(inst, zClass);
            }
            return true;

        } catch (NoSuchFieldException e) {
            System.out.println("[VAR] bB/bC/iX not found: " + e.getMessage());
            // Dump all field names to help identify correct names
            System.out.println("[VAR] All int[] fields in Z:");
            for (Field f : zClass.getDeclaredFields()) {
                if (f.getType() == int[].class) {
                    System.out.println("[VAR]   " + f.getName());
                }
            }
            return false;
        }
    }

    private static void findInstance(Instrumentation inst, Class<?> zClass) {
        System.out.println("[VAR] searching for Z instance...");
        for (Class<?> c : inst.getAllLoadedClasses()) {
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) continue;
                if (f.getType() != zClass) continue;
                f.setAccessible(true);
                try {
                    Object val = f.get(null);
                    if (val != null) {
                        zInstance = val;
                        ready = true;
                        System.out.println("[VAR] Z instance found in "
                                + c.getName() + "." + f.getName());
                        readAndLog();
                        return;
                    }
                } catch (Exception ignored) {}
            }
        }
        System.out.println("[VAR] Z instance not found yet in static fields");

        // Start a thread that keeps retrying until the instance is set
        Thread t = new Thread(() -> {
            for (int i = 0; i < 120; i++) {
                try { Thread.sleep(500); } catch (InterruptedException e) { return; }
                for (Class<?> c : inst.getAllLoadedClasses()) {
                    for (Field f : c.getDeclaredFields()) {
                        if (!Modifier.isStatic(f.getModifiers())) continue;
                        if (f.getType() != zClass) continue;
                        f.setAccessible(true);
                        try {
                            Object val = f.get(null);
                            if (val != null) {
                                zInstance = val;
                                ready = true;
                                System.out.println("[VAR] Z instance found (retry " + i + ") in "
                                        + c.getName() + "." + f.getName());
                                readAndLog();
                                return;
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
            System.out.println("[VAR] gave up finding Z instance");
        }, "VAR-instance-finder");
        t.setDaemon(true);
        t.start();
    }

    private static void readAndLog() {
        try {
            Object inst = fieldsStatic ? null : zInstance;
            int[]  bB   = (int[]) fieldBB.get(inst);
            int[]  bC   = (int[]) fieldBC.get(inst);
            int    iX   = (int)   fieldIX.get(inst);
            System.out.println("[VAR] sample read: iX=" + iX
                    + " bB=" + (bB == null ? "null" : "len="+bB.length)
                    + " bC=" + (bC == null ? "null" : "len="+bC.length));
            if (bB != null && iX > 0) {
                for (int i = 0; i < Math.min(iX, 5); i++) {
                    System.out.println("[VAR]   [" + i + "] x=" + bB[i]
                            + " y=" + (bC != null ? bC[i] : "?"));
                }
            }
        } catch (Exception e) {
            System.out.println("[VAR] sample read failed: " + e);
        }
    }

    /**
     * Called from Hooks.drawOverlayDirect() every frame (via onFrame).
     * Snapshots Z.bB/bC into ProjectionCache.
     */
    public static void onFrame() {
        if (!ready) return;
        long n = frameCount.incrementAndGet();

        try {
            Object obj = fieldsStatic ? null : zInstance;
            int[]  bB  = (int[]) fieldBB.get(obj);
            int[]  bC  = (int[]) fieldBC.get(obj);
            int    iX  = (int)   fieldIX.get(obj);

            // Arrays may be null early in startup — just skip those frames
            if (bB == null || bC == null) {
                if (n < 10) System.out.println("[VAR] frame " + n + " arrays still null");
                return;
            }

            if (iX <= 0) return;

            ProjectionCache.recordVertices(bB, bC, iX);

            // Log on first successful read and periodically after
            if (n == 1 || n % 300 == 0) {
                System.out.println("[VAR] onFrame n=" + n + " iX=" + iX
                        + " sample[0]=(" + bB[0] + "," + bC[0] + ")"
                        + " sample[1]=(" + (iX>1?bB[1]:0) + "," + (iX>1?bC[1]:0) + ")");
            }
        } catch (Exception e) {
            if (n < 5) System.out.println("[VAR] onFrame error: " + e);
        }
    }

    public static boolean isReady() { return ready; }
}