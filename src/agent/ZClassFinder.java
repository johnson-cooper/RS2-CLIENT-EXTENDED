package agent;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Focused finder for the real worldToScreen projection.
 *
 * Evidence so far:
 *  - an.e / an.f (III)I  → NOT screen coords (values 10000+, asymmetric call counts)
 *  - an.d (III)I         → fixed-point math helper (a*b)>>c, not screen coords
 *  - an.e (IIII)V        → draw call receiving screen coords as INPUT args
 *  - I.*                 → 2D pixel/rect drawing primitives
 *  - Z.c (III)I          → appeared 6x in original scan, never tested — prime suspect
 *
 * This transformer:
 *  1. Hooks Z.c(III)I return value — if it's the projector it returns 0..945 / 0..503
 *  2. Hooks an.e(IIII)V entry — logs all 4 args to see if (arg0,arg1) = (screenX,screenY)
 *     and (arg2,arg3) = tile coords or vice versa
 *  3. Hooks Z.g(III)V and Z.h(III)V entry — other Z candidates from original scan
 *
 * Add to AgentMain:
 *   ZClassFinder.install(inst);
 * Remove DrawCallFinder and ScreenProjectionFinder.
 */
public class ZClassFinder implements ClassFileTransformer {

    private static final int MAX_X = 945;
    private static final int MAX_Y = 503;

    private static final ConcurrentHashMap<String, Long> lastPrint = new ConcurrentHashMap<>();

    public static void install(Instrumentation inst) {
        inst.addTransformer(new ZClassFinder(), true);
        System.out.println("[ZFinder] installed");

        String[] targets = { "Z", "an" };
        for (Class<?> c : inst.getAllLoadedClasses()) {
            for (String t : targets) {
                if (c.getName().equals(t)) {
                    try {
                        inst.retransformClasses(c);
                        System.out.println("[ZFinder] retransformed: " + t);
                    } catch (Exception e) {
                        System.out.println("[ZFinder] retransform FAILED " + t + ": " + e);
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    @Override
    public byte[] transform(ClassLoader loader, String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain pd, byte[] bytes) {

        if (!className.equals("Z") && !className.equals("an")) return null;
        System.out.println("[ZFinder] transforming: " + className);

        try {
            ClassReader cr = new ClassReader(bytes);
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            final String cn = className;

            cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc,
                                                 String sig, String[] ex) {
                    MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                    boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;

                    // Z.c(III)I — prime suspect for worldToScreen
                    // Hook return value
                    if (cn.equals("Z") && name.equals("c") && desc.equals("(III)I")) {
                        System.out.println("[ZFinder] hooking Z.c(III)I return");
                        return new ReturnHook(mv, access, name, desc, cn, isStatic);
                    }

                    // Z.g(III)V, Z.h(III)V — other Z candidates, hook entry args
                    if (cn.equals("Z") && desc.equals("(III)V")
                            && (name.equals("g") || name.equals("h"))) {
                        System.out.println("[ZFinder] hooking Z." + name + "(III)V args");
                        return new ArgHook(mv, access, name, desc, cn, isStatic);
                    }

                    // an.e(IIII)V — appeared as VOID ARG HIT with arg0=80 arg1=64
                    // Hook entry to see all 4 args
                    if (cn.equals("an") && name.equals("e") && desc.equals("(IIII)V")) {
                        System.out.println("[ZFinder] hooking an.e(IIII)V args");
                        return new ArgHook4(mv, access, name, desc, cn, isStatic);
                    }

                    return mv;
                }
            }, ClassReader.EXPAND_FRAMES);

            return cw.toByteArray();
        } catch (Exception e) {
            System.out.println("[ZFinder] error in " + className + ": " + e);
            return null;
        }
    }

    // ---- Hook (III)I return value -------------------------------------------

    private static class ReturnHook extends AdviceAdapter {
        private final String cn, mn;
        private final boolean isStatic;

        ReturnHook(MethodVisitor mv, int acc, String name, String desc,
                   String cn, boolean isStatic) {
            super(Opcodes.ASM9, mv, acc, name, desc);
            this.cn = cn; this.mn = name; this.isStatic = isStatic;
        }

        @Override protected void onMethodExit(int opcode) {
            if (opcode != IRETURN) return;
            int base = isStatic ? 0 : 1;
            mv.visitInsn(DUP);
            mv.visitVarInsn(ILOAD, base);
            mv.visitVarInsn(ILOAD, base + 1);
            mv.visitVarInsn(ILOAD, base + 2);
            mv.visitLdcInsn(cn + "." + mn);
            mv.visitMethodInsn(INVOKESTATIC,
                    "agent/ZClassFinder", "onReturn3",
                    "(IIIILjava/lang/String;)V", false);
        }
    }

    // ---- Hook (III)V entry args ---------------------------------------------

    private static class ArgHook extends AdviceAdapter {
        private final String cn, mn, desc;
        private final boolean isStatic;

        ArgHook(MethodVisitor mv, int acc, String name, String desc,
                String cn, boolean isStatic) {
            super(Opcodes.ASM9, mv, acc, name, desc);
            this.cn = cn; this.mn = name; this.desc = desc; this.isStatic = isStatic;
        }

        @Override protected void onMethodEnter() {
            int base = isStatic ? 0 : 1;
            mv.visitVarInsn(ILOAD, base);
            mv.visitVarInsn(ILOAD, base + 1);
            mv.visitVarInsn(ILOAD, base + 2);
            mv.visitLdcInsn(cn + "." + mn + desc);
            mv.visitMethodInsn(INVOKESTATIC,
                    "agent/ZClassFinder", "onArgs3",
                    "(IIILjava/lang/String;)V", false);
        }
    }

    // ---- Hook (IIII)V entry args --------------------------------------------

    private static class ArgHook4 extends AdviceAdapter {
        private final String cn, mn, desc;
        private final boolean isStatic;

        ArgHook4(MethodVisitor mv, int acc, String name, String desc,
                 String cn, boolean isStatic) {
            super(Opcodes.ASM9, mv, acc, name, desc);
            this.cn = cn; this.mn = name; this.desc = desc; this.isStatic = isStatic;
        }

        @Override protected void onMethodEnter() {
            int base = isStatic ? 0 : 1;
            mv.visitVarInsn(ILOAD, base);
            mv.visitVarInsn(ILOAD, base + 1);
            mv.visitVarInsn(ILOAD, base + 2);
            mv.visitVarInsn(ILOAD, base + 3);
            mv.visitLdcInsn(cn + "." + mn + desc);
            mv.visitMethodInsn(INVOKESTATIC,
                    "agent/ZClassFinder", "onArgs4",
                    "(IIIILjava/lang/String;)V", false);
        }
    }

    // ---- Callbacks ----------------------------------------------------------

    /** Called after Z.c(III)I returns. Checks if return value is screen-range. */
    public static void onReturn3(int retVal, int a, int b, int c, String label) {
        // -1 = RS2 off-screen sentinel, also valid
        boolean screenX = (retVal >= 0 && retVal <= MAX_X) || retVal == -1;
        boolean screenY = (retVal >= 0 && retVal <= MAX_Y) || retVal == -1;

        if (!screenX) return; // outside both ranges

        String rangeTag = retVal == -1   ? "OFF_SCREEN"
                        : screenY        ? "0-503 (could be Y)"
                        :                  "504-945 (X only)";

        throttle(label + retVal,
            "[ZFinder] RETURN HIT: " + label + "(III)I"
            + " returned=" + retVal
            + " args=(" + a + "," + b + "," + c + ")"
            + " range=" + rangeTag);
    }

    /** Called on entry to (III)V methods. Checks if first two args are screen coords. */
    public static void onArgs3(int a, int b, int c, String label) {
        boolean aIsX = a >= 10 && a <= MAX_X;
        boolean bIsY = b >= 10 && b <= MAX_Y;
        if (!aIsX || !bIsY) return;

        throttle(label + "_" + (a/50) + "_" + (b/50),
            "[ZFinder] ARG3 HIT: " + label
            + " arg0(x?)=" + a
            + " arg1(y?)=" + b
            + " arg2=" + c);
    }

    /** Called on entry to (IIII)V methods. Logs all 4 args. */
    public static void onArgs4(int a, int b, int c, int d, String label) {
        // Log whenever any two adjacent args look like screen coords
        boolean aIsX = a >= 0 && a <= MAX_X;
        boolean bIsY = b >= 0 && b <= MAX_Y;
        boolean bIsX = b >= 0 && b <= MAX_X;
        boolean cIsY = c >= 0 && c <= MAX_Y;

        if ((aIsX && bIsY && (a > 10 || b > 10))
         || (bIsX && cIsY && (b > 10 || c > 10))) {
            throttle(label + "_" + (a/50),
                "[ZFinder] ARG4 HIT: " + label
                + " args=(" + a + "," + b + "," + c + "," + d + ")"
                + (aIsX && bIsY ? " → (arg0=screenX?, arg1=screenY?)" : "")
                + (bIsX && cIsY ? " → (arg1=screenX?, arg2=screenY?)" : ""));
        }
    }

    private static void throttle(String key, String msg) {
        long now = System.currentTimeMillis();
        Long last = lastPrint.get(key);
        if (last == null || now - last > 1500) {
            lastPrint.put(key, now);
            System.out.println(msg);
        }
    }
}