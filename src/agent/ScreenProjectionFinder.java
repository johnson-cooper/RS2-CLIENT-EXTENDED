package agent;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Diagnostic transformer: hooks EVERY (III)I method across ALL classes
 * and logs which ones return values in screen-pixel range.
 *
 * Screen is 765x503 game pixels. A real worldToScreenX returns 0..765,
 * a real worldToScreenY returns 0..503. We also accept -1 (RS2 off-screen
 * sentinel) as a valid hit.
 *
 * Run this for ~10 seconds of gameplay, then look for lines like:
 *   [ProjFinder] SCREEN-RANGE HIT: class=an method=e desc=(III)I val=382 args=(213,62,0)
 *
 * The class+method that consistently returns values in [0..765] is projX.
 * The one returning [0..503] is projY.
 *
 * Once identified, put those names in CoordinateHook and disable this finder.
 *
 * TO USE:
 *   In AgentMain.premain(), add BEFORE CoordinateHook.install():
 *     ScreenProjectionFinder.install(inst);
 *   Comment out CoordinateHook.install() while this is active.
 */
public class ScreenProjectionFinder implements ClassFileTransformer {

    // Only inspect these classes (from your candidate log).
    // Add more if needed. Keeps noise low.
    private static final String[] TARGET_CLASSES = {
        "an",   // current best guess — e and f
        "Z",    // has Z.c (III)I — appears many times in log
        "aB",   // aB.h (III)I
    };

    // Screen bounds (game pixel space)
    private static final int MAX_X = 765;
    private static final int MAX_Y = 503;

    // Throttle: only print each (class, method, isInRange) combo once per second
    private static final ConcurrentHashMap<String, Long> lastPrint = new ConcurrentHashMap<>();

    // Global call counter for noise suppression
    private static final AtomicLong totalCalls = new AtomicLong();

    public static void install(Instrumentation inst) {
        inst.addTransformer(new ScreenProjectionFinder(), true);
        System.out.println("[ProjFinder] installed — will log (III)I methods returning screen-range values");

        // Retransform already-loaded target classes
        for (Class<?> c : inst.getAllLoadedClasses()) {
            for (String target : TARGET_CLASSES) {
                if (c.getName().equals(target)) {
                    try {
                        inst.retransformClasses(c);
                        System.out.println("[ProjFinder] retransformed: " + target);
                    } catch (Exception e) {
                        System.out.println("[ProjFinder] retransform failed for " + target + ": " + e);
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
        for (String t : TARGET_CLASSES) {
            if (className.equals(t)) { isTarget = true; break; }
        }
        if (!isTarget) return null;

        System.out.println("[ProjFinder] transforming: " + className);

        try {
            ClassReader cr = new ClassReader(bytes);
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            final String cn = className;

            cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc,
                                                 String sig, String[] ex) {
                    MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                    // Hook all (III)I instance methods
                    if (desc.equals("(III)I") && (access & Opcodes.ACC_STATIC) == 0) {
                        return new RangeLogger(mv, access, name, desc, cn);
                    }
                    return mv;
                }
            }, ClassReader.EXPAND_FRAMES);

            return cw.toByteArray();
        } catch (Exception e) {
            System.out.println("[ProjFinder] transform error in " + className + ": " + e);
            return null;
        }
    }

    // -------------------------------------------------------------------------

    private static class RangeLogger extends AdviceAdapter {
        private final String className;
        private final String methodName;

        // Slot layout for instance (III)I: slot0=this, 1=arg0, 2=arg1, 3=arg2
        private static final int SLOT_A = 1;
        private static final int SLOT_B = 2;
        private static final int SLOT_C = 3;

        RangeLogger(MethodVisitor mv, int access, String name, String desc, String cn) {
            super(Opcodes.ASM9, mv, access, name, desc);
            this.className  = cn;
            this.methodName = name;
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (opcode != IRETURN) return;

            // DUP return value, push args, call our logger
            mv.visitInsn(DUP);
            mv.visitVarInsn(ILOAD, SLOT_A);
            mv.visitVarInsn(ILOAD, SLOT_B);
            mv.visitVarInsn(ILOAD, SLOT_C);
            mv.visitLdcInsn(className);
            mv.visitLdcInsn(methodName);

            mv.visitMethodInsn(INVOKESTATIC,
                    "agent/ScreenProjectionFinder",
                    "onReturn",
                    "(IIIILjava/lang/String;Ljava/lang/String;)V",
                    false);
        }
    }

    /**
     * Called from injected bytecode after every (III)I return.
     * Logs methods whose return value falls in screen-pixel range.
     */
    public static void onReturn(int retVal, int a, int b, int c,
                                 String className, String methodName) {
        totalCalls.incrementAndGet();

        // Is this plausibly a screen coordinate?
        boolean inXRange = (retVal >= 0 && retVal <= MAX_X) || retVal == -1;
        boolean inYRange = (retVal >= 0 && retVal <= MAX_Y) || retVal == -1;

        if (!inXRange) return; // outside both ranges entirely, skip

        String key = className + "." + methodName + ":" + (retVal == -1 ? "OFFSCREEN" : (inYRange ? "Y?" : "X?"));
        long now = System.currentTimeMillis();
        Long last = lastPrint.get(key);

        if (last == null || now - last > 1000) {
            lastPrint.put(key, now);

            String rangeHint = retVal == -1 ? "OFF_SCREEN(-1)"
                             : inYRange      ? "IN_Y_RANGE(0-503) — could be projY"
                             :                 "IN_X_RANGE(504-765) — could be projX";

            System.out.println("[ProjFinder] HIT: "
                    + className + "." + methodName + "(III)I"
                    + " returned=" + retVal
                    + " args=(" + a + "," + b + "," + c + ")"
                    + " " + rangeHint);
        }
    }
}