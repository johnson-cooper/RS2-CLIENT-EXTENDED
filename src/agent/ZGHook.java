package agent;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/**
 * Hooks Z.g(IIIII)V to capture arg0 (screenX) and arg1 (screenY).
 *
 * From bytecode analysis of aN.p:
 *   Z.g(IIIII)V is called as Z.g(screenCenterX, screenCenterY, dX, dY, dZ)
 *   where screenCenterX/Y is the projected screen position of the entity/tile.
 *
 * We feed these into ProjectionCache as screen point pairs.
 * No fixed-point shift needed — these are already final screen pixels.
 */
public class ZGHook implements ClassFileTransformer {

    public static void install(Instrumentation inst) {
        inst.addTransformer(new ZGHook(), true);
        System.out.println("[ZGHook] installed");
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (c.getName().equals("Z")) {
                try {
                    inst.retransformClasses(c);
                    System.out.println("[ZGHook] retransformed Z");
                } catch (Exception e) {
                    System.out.println("[ZGHook] retransform failed: " + e);
                }
                break;
            }
        }
    }

    @Override
    public byte[] transform(ClassLoader loader, String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain pd, byte[] bytes) {
        if (!className.equals("Z")) return null;

        System.out.println("[ZGHook] transforming Z");
        try {
            ClassReader cr = new ClassReader(bytes);
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);

            cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc,
                                                 String sig, String[] ex) {
                    MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);

                    // Hook Z.g(IIIII)V — 5 int args, void
                    if (name.equals("g") && desc.equals("(IIIII)V")) {
                        System.out.println("[ZGHook] hooking Z.g(IIIII)V");
                        boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
                        final int base = isStatic ? 0 : 1;

                        return new AdviceAdapter(Opcodes.ASM9, mv, access, name, desc) {
                            @Override
                            protected void onMethodEnter() {
                                // arg0 = screenX, arg1 = screenY
                                mv.visitVarInsn(ILOAD, base);     // screenX
                                mv.visitVarInsn(ILOAD, base + 1); // screenY
                                mv.visitMethodInsn(INVOKESTATIC,
                                        "agent/ZGHook", "onZG",
                                        "(II)V", false);
                            }
                        };
                    }
                    return mv;
                }
            }, ClassReader.EXPAND_FRAMES);

            return cw.toByteArray();
        } catch (Exception e) {
            System.out.println("[ZGHook] error: " + e);
            return null;
        }
    }

    private static final int MAX_X = 1920;
    private static final int MAX_Y = 1009;

    // Rolling sequence for ProjectionCache
    private static volatile int seq = 0;

    // Diagnostics
    private static volatile long lastLog = 0;
    private static volatile int  callCount = 0;
    private static volatile int  inRangeCount = 0;

    public static void onZG(int screenX, int screenY) {
        callCount++;

        // Only store if both coords are plausibly on screen
        boolean xOk = screenX >= -50 && screenX <= MAX_X + 50;
        boolean yOk = screenY >= -50 && screenY <= MAX_Y + 50;

        if (xOk && yOk) {
            inRangeCount++;
            ProjectionCache.recordPair(screenX, screenY, seq);
            seq = (seq + 1) & 0xFFFF;
        }

        // Log once per second
        long now = System.currentTimeMillis();
        if (now - lastLog > 1000) {
            lastLog = now;
            System.out.println("[ZGHook] calls/sec=" + callCount
                    + " inRange=" + inRangeCount
                    + " lastXY=(" + screenX + "," + screenY + ")");
            callCount = 0;
            inRangeCount = 0;
        }
    }
}