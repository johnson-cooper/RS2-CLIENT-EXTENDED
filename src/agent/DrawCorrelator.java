package agent;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Correlates an.e(III)I return values with subsequent I.* draw calls
 * to establish the X→screenX and Y→screenY mapping.
 *
 * Strategy:
 *  1. Hook an.e(III)I — record every raw return value in a small ring buffer
 *  2. Hook I.b/c/d(IIII)V — when a draw call fires with plausible screen
 *     coords, log the last few an.e values alongside it
 *
 * If an.e is the X projector, we expect to see its recent return value
 * matching arg0 of the draw call (after >>4 or some other shift).
 * If Y comes from somewhere else, we'll see the draw arg1 NOT matching
 * any recent an.e value — which tells us to look for another method.
 *
 * Window after stretch: 1920x1009
 */
public class DrawCorrelator implements ClassFileTransformer {

    // Updated bounds based on actual stretched window size
    private static final int MAX_X = 1920;
    private static final int MAX_Y = 1009;

    // Ring buffer: last 8 raw an.e return values before each draw call
    private static final int[]     recentRaw   = new int[8];
    private static volatile int    recentHead  = 0;
    private static final AtomicLong eCalls     = new AtomicLong();

    private static final ConcurrentHashMap<String, Long> throttle = new ConcurrentHashMap<>();

    private static final String[] TARGETS = { "an", "I" };

    public static void install(Instrumentation inst) {
        inst.addTransformer(new DrawCorrelator(), true);
        System.out.println("[DrawCorr] installed — window=1920x1009");
        for (Class<?> c : inst.getAllLoadedClasses()) {
            for (String t : TARGETS) {
                if (c.getName().equals(t)) {
                    try {
                        inst.retransformClasses(c);
                        System.out.println("[DrawCorr] retransformed: " + t);
                    } catch (Exception e) {
                        System.out.println("[DrawCorr] retransform FAILED " + t + ": " + e);
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

        System.out.println("[DrawCorr] transforming: " + className);
        try {
            ClassReader cr = new ClassReader(bytes);
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            final String cn = className;

            cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc,
                                                 String sig, String[] ex) {
                    MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);

                    // Hook an.e return — capture raw value only (no slot loads)
                    if (cn.equals("an") && name.equals("e") && desc.equals("(III)I")) {
                        return new AdviceAdapter(Opcodes.ASM9, mv, access, name, desc) {
                            @Override protected void onMethodExit(int op) {
                                if (op != IRETURN) return;
                                mv.visitInsn(DUP);
                                mv.visitMethodInsn(INVOKESTATIC,
                                        "agent/DrawCorrelator", "onAnE", "(I)V", false);
                            }
                        };
                    }

                    // Hook I.b/c/d(IIII)V entry — capture all 4 args
                    if (cn.equals("I") && desc.equals("(IIII)V")
                            && (name.equals("b") || name.equals("c") || name.equals("d"))) {
                        final String mn = name;
                        boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
                        final int base = isStatic ? 0 : 1;
                        return new AdviceAdapter(Opcodes.ASM9, mv, access, name, desc) {
                            @Override protected void onMethodEnter() {
                                mv.visitVarInsn(ILOAD, base);
                                mv.visitVarInsn(ILOAD, base + 1);
                                mv.visitVarInsn(ILOAD, base + 2);
                                mv.visitVarInsn(ILOAD, base + 3);
                                mv.visitLdcInsn(mn);
                                mv.visitMethodInsn(INVOKESTATIC,
                                        "agent/DrawCorrelator", "onDraw",
                                        "(IIIILjava/lang/String;)V", false);
                            }
                        };
                    }

                    return mv;
                }
            }, ClassReader.EXPAND_FRAMES);

            return cw.toByteArray();
        } catch (Exception e) {
            System.out.println("[DrawCorr] error in " + className + ": " + e);
            return null;
        }
    }

    /** Called after every an.e(III)I return. Stores raw value in ring buffer. */
    public static void onAnE(int raw) {
        eCalls.incrementAndGet();
        recentRaw[recentHead & 7] = raw;
        recentHead++;
    }

    /**
     * Called on entry to I.b/c/d(IIII)V.
     * Logs calls where arg0+arg1 look like screen coords,
     * alongside recent an.e raw values.
     */
    public static void onDraw(int a0, int a1, int a2, int a3, String method) {
        // arg0 and arg1 must both be plausible screen coordinates
        boolean a0ok = a0 >= 10 && a0 <= MAX_X;
        boolean a1ok = a1 >= 10 && a1 <= MAX_Y;
        if (!a0ok || !a1ok) return;

        // Snapshot recent an.e values
        int[] snap = new int[8];
        int head = recentHead;
        for (int i = 0; i < 8; i++) snap[i] = recentRaw[(head - 1 - i) & 7];

        // Build shift analysis: for each shift, does any recent an.e value
        // match a0 (screenX candidate) or a1 (screenY candidate)?
        StringBuilder sb = new StringBuilder();
        sb.append("[DrawCorr] I.").append(method)
          .append(" draw(").append(a0).append(",").append(a1)
          .append(",").append(a2).append(",").append(a3).append(")")
          .append("  recent an.e raw=[");
        for (int i = 0; i < 4; i++) sb.append(snap[i]).append(i<3?",":"");
        sb.append("]  shifts: ");

        for (int shift = 3; shift <= 8; shift++) {
            for (int i = 0; i < 8; i++) {
                int shifted = snap[i] >> shift;
                if (shifted == a0) {
                    sb.append(" >>").append(shift).append("=a0(X!) ");
                    break;
                }
                if (shifted == a1) {
                    sb.append(" >>").append(shift).append("=a1(Y?) ");
                    break;
                }
            }
        }

        String key = method + "_" + (a0/60) + "_" + (a1/60);
        long now = System.currentTimeMillis();
        Long last = throttle.get(key);
        if (last == null || now - last > 1000) {
            throttle.put(key, now);
            System.out.println(sb.toString());
        }
    }
}