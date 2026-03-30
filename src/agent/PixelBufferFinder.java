package agent;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hooks F.d(III)V and F.e(III)V — the symmetric pair that appeared 57 times
 * each in the original scan. In RS2, the 3D scene is rendered directly to an
 * int[] pixel buffer, not via Graphics. These void (III) methods are the
 * prime candidates for drawScanline(x, y, color) or fillSpan(x, width, color).
 *
 * We log calls where arg0 and arg1 are both in screen-pixel range to find
 * which argument is X and which is Y. We also check if arg0 alone is in
 * range (could be drawHorizontalLine(x, length, color)).
 *
 * Also hooks F.a and F.b which appeared with different signatures.
 *
 * Expected output if F.d/e are the tile draw calls:
 *   [PBF] F.d(341,200,color) — arg0=x, arg1=y both in range
 * or:
 *   [PBF] F.d(341,64,color) — arg0=x, arg1=scanline
 */
public class PixelBufferFinder implements ClassFileTransformer {

    private static final int MAX_X = 1920;
    private static final int MAX_Y = 1009;

    private static final ConcurrentHashMap<String, Long>     throttle = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicLong> counts  = new ConcurrentHashMap<>();

    // Last an.e raw value — to correlate with pixel writes
    public static volatile int lastAnERaw = -1;

    private static final String[] TARGETS = { "F", "an" };

    public static void install(Instrumentation inst) {
        inst.addTransformer(new PixelBufferFinder(), true);
        System.out.println("[PBF] installed");
        for (Class<?> c : inst.getAllLoadedClasses()) {
            for (String t : TARGETS) {
                if (c.getName().equals(t)) {
                    try {
                        inst.retransformClasses(c);
                        System.out.println("[PBF] retransformed: " + t);
                    } catch (Exception e) {
                        System.out.println("[PBF] retransform FAILED " + t + ": " + e);
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

        System.out.println("[PBF] transforming: " + className);
        try {
            ClassReader cr = new ClassReader(bytes);
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            final String cn = className;

            cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc,
                                                 String sig, String[] ex) {
                    MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                    boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
                    final int base = isStatic ? 0 : 1;

                    // Capture an.e raw return — no slot loads
                    if (cn.equals("an") && name.equals("e") && desc.equals("(III)I")) {
                        return new AdviceAdapter(Opcodes.ASM9, mv, access, name, desc) {
                            @Override protected void onMethodExit(int op) {
                                if (op != IRETURN) return;
                                mv.visitInsn(DUP);
                                mv.visitMethodInsn(INVOKESTATIC,
                                        "agent/PixelBufferFinder", "onAnE", "(I)V", false);
                            }
                        };
                    }

                    // Hook F.d(III)V and F.e(III)V — pixel buffer draw candidates
                    if (cn.equals("F") && desc.equals("(III)V")
                            && (name.equals("d") || name.equals("e"))) {
                        System.out.println("[PBF] hooking F." + name + "(III)V");
                        final String mn = name;
                        return new AdviceAdapter(Opcodes.ASM9, mv, access, name, desc) {
                            @Override protected void onMethodEnter() {
                                mv.visitVarInsn(ILOAD, base);
                                mv.visitVarInsn(ILOAD, base + 1);
                                mv.visitVarInsn(ILOAD, base + 2);
                                mv.visitLdcInsn(mn);
                                mv.visitMethodInsn(INVOKESTATIC,
                                        "agent/PixelBufferFinder", "onFdraw",
                                        "(IIILjava/lang/String;)V", false);
                            }
                        };
                    }

                    // Hook F.a(ZIIIII)V and F.a(IIIIZ)V from original scan
                    if (cn.equals("F") && name.equals("a")
                            && (desc.equals("(ZIIIII)V") || desc.equals("(IIIIZ)V"))) {
                        System.out.println("[PBF] hooking F.a" + desc);
                        final String dn = desc;
                        return new AdviceAdapter(Opcodes.ASM9, mv, access, name, desc) {
                            @Override protected void onMethodEnter() {
                                // First two int args (skip boolean if first)
                                int slot = base;
                                if (dn.startsWith("(Z")) slot++; // skip boolean
                                mv.visitVarInsn(ILOAD, slot);
                                mv.visitVarInsn(ILOAD, slot + 1);
                                mv.visitLdcInsn("F.a" + dn);
                                mv.visitMethodInsn(INVOKESTATIC,
                                        "agent/PixelBufferFinder", "onFa",
                                        "(IILjava/lang/String;)V", false);
                            }
                        };
                    }

                    return mv;
                }
            }, ClassReader.EXPAND_FRAMES);

            return cw.toByteArray();
        } catch (Exception e) {
            System.out.println("[PBF] error in " + className + ": " + e);
            return null;
        }
    }

    public static void onAnE(int raw) {
        lastAnERaw = raw;
    }

    public static void onFdraw(int a0, int a1, int a2, String method) {
        counts.computeIfAbsent("F." + method, k -> new AtomicLong()).incrementAndGet();

        // Check both orderings: (x,y,color) or (y,x,color) or (x,width,color)
        boolean a0isX = a0 >= 10 && a0 <= MAX_X;
        boolean a1isY = a1 >= 10 && a1 <= MAX_Y;
        boolean a0isY = a0 >= 10 && a0 <= MAX_Y;
        boolean a1isX = a1 >= 10 && a1 <= MAX_X;

        if (!a0isX && !a0isY) return; // a0 completely out of range

        // Correlate with last an.e value
        String corr = "";
        int anE = lastAnERaw;
        if (anE > 0) {
            for (int sh = 3; sh <= 8; sh++) {
                if ((anE >> sh) == a0) { corr = " anE>>"+sh+"==a0(X!)"; break; }
                if ((anE >> sh) == a1) { corr = " anE>>"+sh+"==a1"; break; }
            }
        }

        String key = "F." + method + "_" + (a0/80) + "_" + (a1/80);
        long now = System.currentTimeMillis();
        Long last = throttle.get(key);
        if (last != null && now - last < 800) return;
        throttle.put(key, now);

        String layout = (a0isX && a1isY) ? " [x,y,color]"
                      : (a0isY && a1isX) ? " [y,x,color]"
                      : a0isX            ? " [x,?,color]"
                      :                    " [?,?,?]";

        System.out.println("[PBF] F." + method + "(" + a0 + "," + a1 + "," + a2 + ")"
                + layout + corr
                + " calls=" + counts.get("F." + method).get());
    }

    public static void onFa(int a0, int a1, String label) {
        boolean a0ok = a0 >= 10 && a0 <= MAX_X;
        boolean a1ok = a1 >= 10 && a1 <= MAX_Y;
        if (!a0ok || !a1ok) return;
        String key = label + "_" + (a0/80) + "_" + (a1/80);
        long now = System.currentTimeMillis();
        Long last = throttle.get(key);
        if (last != null && now - last < 800) return;
        throttle.put(key, now);
        System.out.println("[PBF] " + label + " args=(" + a0 + "," + a1 + ",...)");
    }
}