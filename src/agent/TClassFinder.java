package agent;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hooks t.a([IIIII)I and t.b([IIIII)I — the strongest remaining worldToScreen
 * candidates based on the original scan log.
 *
 * Signature ([IIIII)I means: int[] array, int, int, int, int → int
 * In RS2 this matches: worldToScreen(int[] heightmap, int localX, int localY,
 *                                    int tileX, int tileY) → screenCoord
 *
 * We log:
 *   - The return value (should be 0..945 for X, 0..503 for Y if screen coord)
 *   - Args 1-4 (the ints, skip the array)
 *   - Call rate (to check if a and b are called symmetrically — if so, likely X+Y pair)
 *
 * Also hooks s.b([III)I and r.a([III)I from the scan — similar array+int signatures.
 */
public class TClassFinder implements ClassFileTransformer {

    private static final int MAX_X = 945;
    private static final int MAX_Y = 503;

    private static final String[] TARGETS = { "t" }; // s/r excluded — VerifyError on slot count

    private static final ConcurrentHashMap<String, Long>  lastPrint  = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicLong> callCount = new ConcurrentHashMap<>();

    public static void install(Instrumentation inst) {
        inst.addTransformer(new TClassFinder(), true);
        System.out.println("[TFinder] installed");

        for (Class<?> c : inst.getAllLoadedClasses()) {
            for (String t : TARGETS) {
                if (c.getName().equals(t)) {
                    try {
                        inst.retransformClasses(c);
                        System.out.println("[TFinder] retransformed: " + t);
                    } catch (Exception e) {
                        System.out.println("[TFinder] retransform FAILED " + t + ": " + e);
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

        System.out.println("[TFinder] transforming: " + className);
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

                    // t.a([IIIII)I and t.b([IIIII)I
                    if (cn.equals("t") && desc.equals("([IIIII)I")
                            && (name.equals("a") || name.equals("b"))) {
                        System.out.println("[TFinder] hooking " + cn + "." + name + desc);
                        return new ArrayIntReturnHook(mv, access, name, desc, cn, isStatic);
                    }

                    // s.b([III)I and r.a([III)I
                    if ((cn.equals("s") || cn.equals("r")) && desc.equals("([III)I")) {
                        System.out.println("[TFinder] hooking " + cn + "." + name + desc);
                        return new ArrayIntReturnHook(mv, access, name, desc, cn, isStatic);
                    }

                    return mv;
                }
            }, ClassReader.EXPAND_FRAMES);

            return cw.toByteArray();
        } catch (Exception e) {
            System.out.println("[TFinder] error in " + className + ": " + e);
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Hooks a method that starts with an int[] arg followed by ints, returns int.
    // Slot layout (instance method):
    //   0=this, 1=[I (array), 2=int, 3=int, 4=int, 5=int  (for [IIIII)I)
    //   0=this, 1=[I (array), 2=int, 3=int, 4=int          (for [III)I)
    // -------------------------------------------------------------------------
    private static class ArrayIntReturnHook extends AdviceAdapter {
        private final String cn, mn, desc;
        private final boolean isStatic;

        ArrayIntReturnHook(MethodVisitor mv, int acc, String name, String desc,
                           String cn, boolean isStatic) {
            super(Opcodes.ASM9, mv, acc, name, desc);
            this.cn = cn; this.mn = name; this.desc = desc; this.isStatic = isStatic;
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (opcode != IRETURN) return;

            // base=1 for instance, 0 for static; then skip the [I slot (1 slot)
            int base = isStatic ? 0 : 1;
            int firstInt = base + 1; // skip the array ref

            // How many plain int (I) args follow the array?
            // Parse descriptor: strip leading ( and the [I array arg, count remaining I before )
            int intArgCount = 0;
            String args = desc.substring(1, desc.indexOf(')')); // e.g. "[IIIII"
            boolean skipNext = false;
            for (int ci = 0; ci < args.length(); ci++) {
                char ch = args.charAt(ci);
                if (ch == '[') { skipNext = true; continue; }
                if (skipNext)  { skipNext = false; continue; } // skip array element type
                if (ch == 'I') intArgCount++;
            }

            // DUP return value
            mv.visitInsn(DUP);

            // Push up to 4 int args (pad with -1 if fewer)
            for (int i = 0; i < 4; i++) {
                if (i < intArgCount) {
                    mv.visitVarInsn(ILOAD, firstInt + i);
                } else {
                    mv.visitIntInsn(BIPUSH, -1);
                }
            }

            mv.visitLdcInsn(cn + "." + mn + desc);

            mv.visitMethodInsn(INVOKESTATIC,
                    "agent/TClassFinder", "onReturn",
                    "(IIIIILjava/lang/String;)V", false);
        }
    }

    // ---- Callback -----------------------------------------------------------

    public static void onReturn(int retVal, int a, int b, int c, int d, String label) {
        // Track call counts per method
        callCount.computeIfAbsent(label, k -> new AtomicLong()).incrementAndGet();

        boolean inX = (retVal >= 0 && retVal <= MAX_X) || retVal == -1;
        boolean inY = (retVal >= 0 && retVal <= MAX_Y) || retVal == -1;

        if (!inX) return; // outside screen range entirely

        String rangeTag = retVal == -1 ? "OFF_SCREEN(-1)"
                        : inY          ? "0-503 → projY candidate"
                        :                "504-945 → projX candidate";

        // Throttle per (label + return-value-bucket) so we see variety
        String key = label + "_" + (retVal / 20);
        long now = System.currentTimeMillis();
        Long last = lastPrint.get(key);
        if (last != null && now - last < 1000) return;
        lastPrint.put(key, now);

        System.out.println("[TFinder] HIT: " + label
                + " returned=" + retVal
                + " args=(" + a + "," + b + "," + c + "," + d + ")"
                + " [" + rangeTag + "]"
                + " calls=" + callCount.get(label).get());
    }
}