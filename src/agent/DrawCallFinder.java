package agent;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Diagnostic: hooks void methods with int args in class `an` to find
 * which ones receive screen-range X/Y coordinates as their first two args.
 *
 * RS2's tile rendering likely calls something like:
 *   drawTileOutline(int screenX, int screenY, int color, ...)
 * rather than returning screen coords from a projection function.
 *
 * We watch for calls where arg0 is in [0..945] and arg1 is in [0..503]
 * (window pixel range after stretch), logging class+method+args.
 *
 * Also hooks an.d(III)I which we haven't tested yet.
 */
public class DrawCallFinder implements ClassFileTransformer {

    private static final String[] TARGETS = { "an", "I", "Z", "aB", "F" };

    // Stretched window size (update if yours differs)
    private static final int MAX_X = 945;
    private static final int MAX_Y = 503;

    private static final ConcurrentHashMap<String, Long> lastPrint = new ConcurrentHashMap<>();

    public static void install(Instrumentation inst) {
        inst.addTransformer(new DrawCallFinder(), true);
        System.out.println("[DrawFinder] installed");
        for (Class<?> c : inst.getAllLoadedClasses()) {
            String n = c.getName();
            for (String t : TARGETS) {
                if (n.equals(t)) {
                    try {
                        inst.retransformClasses(c);
                        System.out.println("[DrawFinder] retransformed: " + n);
                    } catch (Exception e) {
                        System.out.println("[DrawFinder] retransform failed " + n + ": " + e);
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

        System.out.println("[DrawFinder] transforming: " + className);
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

                    // Hook (III)I — check if an.d returns screen values
                    if (desc.equals("(III)I") && cn.equals("an") && name.equals("d")) {
                        System.out.println("[DrawFinder] hooking an.d(III)I");
                        return new ReturnLogger(mv, access, name, desc, cn, isStatic);
                    }

                    // Hook void methods with 3-6 int args — look for draw calls
                    // where first two args are screen X, Y
                    if (desc.equals("(IIIV)")   || desc.equals("(IIII)V")  ||
                        desc.equals("(IIIII)V") || desc.equals("(IIIIII)V")||
                        desc.equals("(III)V")) {
                        return new ArgLogger(mv, access, name, desc, cn, isStatic);
                    }
                    return mv;
                }
            }, ClassReader.EXPAND_FRAMES);

            return cw.toByteArray();
        } catch (Exception e) {
            System.out.println("[DrawFinder] error in " + className + ": " + e);
            return null;
        }
    }

    // ---- Logs return value of (III)I methods --------------------------------

    private static class ReturnLogger extends AdviceAdapter {
        private final String cn, mn;
        private final boolean isStatic;

        ReturnLogger(MethodVisitor mv, int acc, String name, String desc,
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
            mv.visitLdcInsn(cn);
            mv.visitLdcInsn(mn);
            mv.visitMethodInsn(INVOKESTATIC,
                    "agent/DrawCallFinder", "onReturn",
                    "(IIIILjava/lang/String;Ljava/lang/String;)V", false);
        }
    }

    // ---- Logs first two args of void methods --------------------------------

    private static class ArgLogger extends AdviceAdapter {
        private final String cn, mn, desc;
        private final boolean isStatic;

        ArgLogger(MethodVisitor mv, int acc, String name, String desc,
                  String cn, boolean isStatic) {
            super(Opcodes.ASM9, mv, acc, name, desc);
            this.cn = cn; this.mn = name; this.desc = desc; this.isStatic = isStatic;
        }

        @Override protected void onMethodEnter() {
            int base = isStatic ? 0 : 1;
            mv.visitVarInsn(ILOAD, base);     // arg0 — potential screen X
            mv.visitVarInsn(ILOAD, base + 1); // arg1 — potential screen Y
            mv.visitLdcInsn(cn);
            mv.visitLdcInsn(mn);
            mv.visitLdcInsn(desc);
            mv.visitMethodInsn(INVOKESTATIC,
                    "agent/DrawCallFinder", "onArgs",
                    "(IILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V", false);
        }
    }

    // ---- Callbacks ----------------------------------------------------------

    public static void onReturn(int retVal, int a, int b, int c,
                                String cn, String mn) {
        boolean inX = retVal >= 0 && retVal <= MAX_X;
        boolean inY = retVal >= 0 && retVal <= MAX_Y;
        if (!inX) return;
        throttledLog(cn + "." + mn + "_ret=" + retVal,
                "[DrawFinder] (III)I HIT: " + cn + "." + mn
                + " returned=" + retVal
                + " args=(" + a + "," + b + "," + c + ")"
                + (inY ? " IN_Y_RANGE" : " X_ONLY_RANGE"));
    }

    public static void onArgs(int arg0, int arg1, String cn, String mn, String desc) {
        // Both first two args must be plausible screen coords simultaneously
        boolean arg0isX = arg0 >= 0 && arg0 <= MAX_X;
        boolean arg1isY = arg1 >= 0 && arg1 <= MAX_Y;
        if (!arg0isX || !arg1isY) return;
        // Suppress near-zero noise (0,0 is common in non-screen calls)
        if (arg0 < 10 && arg1 < 10) return;

        throttledLog(cn + "." + mn + desc,
                "[DrawFinder] VOID ARG HIT: " + cn + "." + mn + desc
                + " arg0(x?)=" + arg0 + " arg1(y?)=" + arg1);
    }

    private static void throttledLog(String key, String msg) {
        long now = System.currentTimeMillis();
        Long last = lastPrint.get(key);
        if (last == null || now - last > 2000) {
            lastPrint.put(key, now);
            System.out.println(msg);
        }
    }
}