package agent;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.concurrent.ConcurrentHashMap;
import java.security.ProtectionDomain;

/**
 * Tests fixed-point hypothesis on an.e and an.f.
 * Only captures the return value — does NOT load any arg slots,
 * avoiding VerifyError from uncertain slot layouts in obfuscated methods.
 */
public class FixedPointFinder implements ClassFileTransformer {

    private static final int MAX_X = 945;
    private static final int MAX_Y = 503;
    private static final int[] SHIFTS = { 4, 5, 6, 7, 8 };

    private static final ConcurrentHashMap<String, Long> throttle = new ConcurrentHashMap<>();

    public static void install(Instrumentation inst) {
        inst.addTransformer(new FixedPointFinder(), true);
        System.out.println("[FPFinder] installed");
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (c.getName().equals("an")) {
                try {
                    inst.retransformClasses(c);
                    System.out.println("[FPFinder] retransformed: an");
                } catch (Exception e) {
                    System.out.println("[FPFinder] retransform FAILED: " + e);
                }
                break;
            }
        }
    }

    @Override
    public byte[] transform(ClassLoader loader, String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain pd, byte[] bytes) {
        if (!className.equals("an")) return null;
        System.out.println("[FPFinder] transforming: an");
        try {
            ClassReader cr = new ClassReader(bytes);
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);

            cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc,
                                                 String sig, String[] ex) {
                    MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                    if (desc.equals("(III)I") && (name.equals("e") || name.equals("f"))) {
                        System.out.println("[FPFinder] hooking an." + name + "(III)I");
                        return new RetValOnlyProbe(mv, access, name, desc, name.equals("e"));
                    }
                    return mv;
                }
            }, ClassReader.EXPAND_FRAMES);

            return cw.toByteArray();
        } catch (Exception e) {
            System.out.println("[FPFinder] error: " + e);
            return null;
        }
    }

    /** Only DUPs the return value — never touches local variable slots. */
    private static class RetValOnlyProbe extends AdviceAdapter {
        private final boolean isE;

        RetValOnlyProbe(MethodVisitor mv, int acc, String name, String desc, boolean isE) {
            super(Opcodes.ASM9, mv, acc, name, desc);
            this.isE = isE;
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (opcode != IRETURN) return;
            // Only DUP the return value — zero local slot loads, no VerifyError risk
            mv.visitInsn(DUP);
            mv.visitInsn(isE ? ICONST_1 : ICONST_0);
            mv.visitMethodInsn(INVOKESTATIC,
                    "agent/FixedPointFinder", "onReturn",
                    "(IZ)V", false);
        }
    }

    public static void onReturn(int raw, boolean isE) {
        String method = "an." + (isE ? "e" : "f");
        for (int shift : SHIFTS) {
            int shifted = raw >> shift;
            boolean inX = shifted >= 0 && shifted <= MAX_X;
            boolean inY = shifted >= 0 && shifted <= MAX_Y;
            if (!inX) continue;

            String rangeLabel = inY ? "IN_Y_RANGE(0-503)" : "IN_X_RANGE(504-945)";
            String key = method + "_sh" + shift + "_" + (shifted / 40);
            long now = System.currentTimeMillis();
            Long last = throttle.get(key);
            if (last != null && now - last < 1500) continue;
            throttle.put(key, now);

            System.out.println("[FPFinder] HIT: " + method
                    + " raw=" + raw + " >>" + shift + "=" + shifted
                    + " " + rangeLabel);
        }
    }
}