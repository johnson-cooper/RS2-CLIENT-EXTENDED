package agent;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hooks aN methods to find where screen coordinates are computed.
 *
 * New hypothesis: RS2 doesn't have a worldToScreen(x,y)→int function.
 * Instead it has a projectTile(tileX, tileY, plane) that computes screen
 * coords and stores them in static fields on class aN (or similar).
 * The draw calls then read those static fields.
 *
 * We hook:
 *   aN.p(III)V  — 3 int args, void, called directly from `an` render loop
 *                 likely: projectAndDrawTile(tileX, tileY, plane)
 *
 * And we scan ALL static int fields on aN after each call to aN.p,
 * looking for fields whose values fall in screen coordinate range.
 *
 * ALSO: hooks I.c(IIII)V and I.b(IIII)V — the confirmed 2D draw primitives —
 * and cross-references their arg0/arg1 with whatever aN.p was last called with,
 * to establish the tileX/tileY → screenX/screenY mapping.
 */
public class ANFinder implements ClassFileTransformer {

    private static final int MAX_X = 945;
    private static final int MAX_Y = 503;

    // Last tile coords passed to aN.p
    private static volatile int lastTileX = -1;
    private static volatile int lastTileY = -1;
    private static volatile int lastPlane = -1;

    private static final ConcurrentHashMap<String, Long>     throttle  = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicLong> counts   = new ConcurrentHashMap<>();

    private static final String[] TARGETS = { "aN", "I" };

    public static void install(Instrumentation inst) {
        inst.addTransformer(new ANFinder(), true);
        System.out.println("[ANFinder] installed");

        for (Class<?> c : inst.getAllLoadedClasses()) {
            for (String t : TARGETS) {
                if (c.getName().equals(t)) {
                    try {
                        inst.retransformClasses(c);
                        System.out.println("[ANFinder] retransformed: " + t);
                    } catch (Exception e) {
                        System.out.println("[ANFinder] retransform FAILED " + t + ": " + e);
                    }
                }
            }
        }
    }

    @Override
    public byte[] transform(ClassLoader loader, String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain pd, byte[] bytes) {

        boolean isTarget = false;
        for (String t : TARGETS) if (className.equals(t)) { isTarget = true; break; }
        if (!isTarget) return null;

        System.out.println("[ANFinder] transforming: " + className);
        try {
            ClassReader cr = new ClassReader(bytes);
            // Do NOT use COMPUTE_FRAMES — it forces ASM to load referenced classes
            // via the classloader, which causes LinkageError (duplicate class definition)
            // when the class being transformed is itself referenced during frame computation.
            // COMPUTE_MAXS is sufficient: it recalculates stack depths without class loading.
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            final String cn = className;

            cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc,
                                                 String sig, String[] ex) {
                    MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                    boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;

                    // aN.p(III)V — project tile, called from render loop
                    if (cn.equals("aN") && name.equals("p") && desc.equals("(III)V")) {
                        System.out.println("[ANFinder] hooking aN.p(III)V");
                        return new TileEntryHook(mv, access, name, desc, isStatic);
                    }

                    // aN.a(IIIIIIII)V — another render candidate (8 ints)
                    if (cn.equals("aN") && name.equals("a") && desc.equals("(IIIIIIII)V")) {
                        System.out.println("[ANFinder] hooking aN.a(IIIIIIII)V");
                        return new EightIntEntryHook(mv, access, name, desc, isStatic);
                    }

                    // I.b(IIII)V and I.c(IIII)V — confirmed draw primitives
                    if (cn.equals("I") && desc.equals("(IIII)V")
                            && (name.equals("b") || name.equals("c") || name.equals("d"))) {
                        System.out.println("[ANFinder] hooking I." + name + "(IIII)V");
                        return new DrawCallHook(mv, access, name, desc, isStatic);
                    }

                    return mv;
                }
            }, ClassReader.EXPAND_FRAMES);

            return cw.toByteArray();
        } catch (Exception e) {
            System.out.println("[ANFinder] error in " + className + ": " + e);
            return null;
        }
    }

    // ---- Hook aN.p(III)V entry — record tile coords -------------------------

    private static class TileEntryHook extends AdviceAdapter {
        private final boolean isStatic;

        TileEntryHook(MethodVisitor mv, int acc, String name, String desc, boolean isStatic) {
            super(Opcodes.ASM9, mv, acc, name, desc);
            this.isStatic = isStatic;
        }

        @Override
        protected void onMethodEnter() {
            int base = isStatic ? 0 : 1;
            mv.visitVarInsn(ILOAD, base);
            mv.visitVarInsn(ILOAD, base + 1);
            mv.visitVarInsn(ILOAD, base + 2);
            mv.visitMethodInsn(INVOKESTATIC,
                    "agent/ANFinder", "onTileProject",
                    "(III)V", false);
        }
    }

    // ---- Hook aN.a(IIIIIIII)V entry — log first 4 ints ---------------------

    private static class EightIntEntryHook extends AdviceAdapter {
        private final boolean isStatic;

        EightIntEntryHook(MethodVisitor mv, int acc, String name, String desc, boolean isStatic) {
            super(Opcodes.ASM9, mv, acc, name, desc);
            this.isStatic = isStatic;
        }

        @Override
        protected void onMethodEnter() {
            int base = isStatic ? 0 : 1;
            mv.visitVarInsn(ILOAD, base);
            mv.visitVarInsn(ILOAD, base + 1);
            mv.visitVarInsn(ILOAD, base + 2);
            mv.visitVarInsn(ILOAD, base + 3);
            mv.visitMethodInsn(INVOKESTATIC,
                    "agent/ANFinder", "onEightInt",
                    "(IIII)V", false);
        }
    }

    // ---- Hook I.b/c/d(IIII)V entry — capture screen coords -----------------

    private static class DrawCallHook extends AdviceAdapter {
        private final String mn;
        private final boolean isStatic;

        DrawCallHook(MethodVisitor mv, int acc, String name, String desc, boolean isStatic) {
            super(Opcodes.ASM9, mv, acc, name, desc);
            this.mn = name;
            this.isStatic = isStatic;
        }

        @Override
        protected void onMethodEnter() {
            int base = isStatic ? 0 : 1;
            mv.visitVarInsn(ILOAD, base);
            mv.visitVarInsn(ILOAD, base + 1);
            mv.visitVarInsn(ILOAD, base + 2);
            mv.visitVarInsn(ILOAD, base + 3);
            mv.visitLdcInsn(mn);
            mv.visitMethodInsn(INVOKESTATIC,
                    "agent/ANFinder", "onDraw",
                    "(IIIILjava/lang/String;)V", false);
        }
    }

    // ---- Callbacks ----------------------------------------------------------

    /** Called when aN.p(tileX, tileY, plane) is entered. */
    public static void onTileProject(int tileX, int tileY, int plane) {
        lastTileX = tileX;
        lastTileY = tileY;
        lastPlane = plane;

        counts.computeIfAbsent("aN.p", k -> new AtomicLong()).incrementAndGet();

        throttleLog("aN.p_" + tileX + "_" + tileY,
                "[ANFinder] aN.p called: tileX=" + tileX
                + " tileY=" + tileY + " plane=" + plane
                + " total=" + counts.get("aN.p").get());
    }

    /** Called when aN.a(IIIIIIII)V is entered — log first 4 args. */
    public static void onEightInt(int a, int b, int c, int d) {
        boolean aX = a >= 10 && a <= MAX_X;
        boolean bY = b >= 10 && b <= MAX_Y;
        if (!aX || !bY) return;
        throttleLog("aN.a8_" + (a/30) + "_" + (b/30),
                "[ANFinder] aN.a(IIIIIIII)V args0-3=(" + a + "," + b + "," + c + "," + d + ")"
                + " ← arg0 in X range, arg1 in Y range — possible screenX,screenY!");
    }

    /**
     * Called when I.b/c/d(IIII)V is entered.
     * If arg0/arg1 look like screen coords AND we recently saw an aN.p call,
     * log the tile→screen mapping.
     */
    public static void onDraw(int x, int y, int w, int h, String method) {
        boolean xOk = x >= 10 && x <= MAX_X;
        boolean yOk = y >= 10 && y <= MAX_Y;
        if (!xOk || !yOk) return;

        if (lastTileX != -1) {
            throttleLog("draw_" + (x/30) + "_" + (y/30),
                    "[ANFinder] DRAW after tile project: I." + method
                    + "  screenXY=(" + x + "," + y + ")"
                    + "  lastTile=(" + lastTileX + "," + lastTileY + "," + lastPlane + ")"
                    + "  size=(" + w + "," + h + ")");
        }
    }

    private static void throttleLog(String key, String msg) {
        long now = System.currentTimeMillis();
        Long last = throttle.get(key);
        if (last == null || now - last > 1500) {
            throttle.put(key, now);
            System.out.println(msg);
        }
    }
}