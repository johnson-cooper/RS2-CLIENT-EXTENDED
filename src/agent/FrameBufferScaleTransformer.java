package agent;

import org.objectweb.asm.*;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * Injects scale into the RS2 framebuffer class.
 *
 * Key fix: we no longer assume the render method is called "paint".
 * Instead, we scan every method for a Graphics.drawImage call, and in
 * THAT method we inject registerGraphics() at the top + applyScale()
 * before the drawImage. This works regardless of obfuscated method names.
 */
public class FrameBufferScaleTransformer implements ClassFileTransformer {

    private final String explicitTarget;
    private volatile String selectedTarget;

    public FrameBufferScaleTransformer() {
        String prop = System.getProperty("agent.target");
        this.explicitTarget = (prop == null || prop.isEmpty())
                ? null : prop.replace('.', '/');
    }

    @Override
    public byte[] transform(ClassLoader loader, String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] bytes) {

        if (className == null || bytes == null) return null;

        try {
            if (explicitTarget != null) {
                if (!className.equals(explicitTarget)) return null;
                if (selectedTarget == null) {
                    selectedTarget = className;
                    System.out.println("[Target] forced = " + selectedTarget);
                }
            }

            if (selectedTarget == null) {
                int s = score(bytes);
                if (s >= 60) {
                    selectedTarget = className;
                    System.out.println("[Target] auto-chosen = " + selectedTarget + "  score=" + s);
                } else {
                    return null;
                }
            }

            if (!className.equals(selectedTarget)) return null;

            System.out.println("[Hook] injecting into: " + className);
            return inject(bytes);

        } catch (Throwable t) {
            t.printStackTrace();
            return null;
        }
    }

    // =========================================================
    // PASS 1: find which methods contain drawImage calls
    // =========================================================

    private static java.util.Set<String> findRenderMethods(byte[] bytes) {
        java.util.Set<String> methods = new java.util.HashSet<>();

        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                // Must accept a Graphics parameter OR return void with no args
                // (obfuscated render methods often take Graphics as an arg)
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner,
                                                String mname, String mdesc, boolean itf) {
                        if (mname.equals("drawImage")
                                && (owner.equals("java/awt/Graphics")
                                || owner.equals("java/awt/Graphics2D")
                                || owner.equals("sun/java2d/SunGraphics2D"))) {
                            methods.add(name + desc);
                            System.out.println("[Scan] drawImage found in method: "
                                    + name + desc);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        return methods;
    }

    // =========================================================
    // PASS 2: inject into those methods
    // =========================================================

    private byte[] inject(byte[] bytes) {
        // First pass: find which methods call drawImage
        java.util.Set<String> renderMethods = findRenderMethods(bytes);

        if (renderMethods.isEmpty()) {
            System.out.println("[Hook] WARNING: no drawImage methods found, skipping");
            return null;
        }

        ClassReader cr = new ClassReader(bytes);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {

                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);

                String key = name + desc;
                if (!renderMethods.contains(key)) return mv; // not a render method, skip

                System.out.println("[Inject] hooking render method: " + name + desc);

                // Find the index of the Graphics parameter in this method's args.
                // e.g. (Ljava/awt/Graphics;)V  → index 1 (index 0 = this)
                //      (ILjava/awt/Graphics;)V → index 2
                //      ()V                     → -1 (no Graphics arg; use ThreadLocal fallback)
                int graphicsArgIndex = findGraphicsArg(access, desc);

                return new MethodVisitor(Opcodes.ASM9, mv) {

                    @Override
                    public void visitCode() {
                        super.visitCode();

                        if (graphicsArgIndex >= 0) {
                            // Register the Graphics arg at the top of the method
                            mv.visitVarInsn(Opcodes.ALOAD, graphicsArgIndex);
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "agent/ScaleHelper",
                                    "registerGraphics",
                                    "(Ljava/awt/Graphics;)V",
                                    false);
                            System.out.println("[Inject] registerGraphics(arg " 
                                    + graphicsArgIndex + ") in " + name);
                        } else {
                            System.out.println("[Inject] no Graphics arg in " + name
                                    + " — will use frame fallback");
                        }
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner,
                                                String mname, String mdesc, boolean itf) {

                        boolean isDrawImage = mname.equals("drawImage")
                                && (owner.equals("java/awt/Graphics")
                                || owner.equals("java/awt/Graphics2D")
                                || owner.equals("sun/java2d/SunGraphics2D"));

                        if (isDrawImage) {
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "agent/ScaleHelper",
                                    "applyScale",
                                    "()V",
                                    false);
                            System.out.println("[Inject] applyScale before drawImage in " + name);
                        }

                        super.visitMethodInsn(opcode, owner, mname, mdesc, itf);
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        if (isReturnOpcode(opcode)) {
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "agent/ScaleHelper",
                                    "clearGraphics",
                                    "()V",
                                    false);
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);

        return cw.toByteArray();
    }

    /**
     * Find the local variable index of the first Graphics parameter.
     * Returns -1 if there is none.
     *
     * For instance methods: index 0 = this, args start at 1.
     * longs/doubles consume two slots.
     */
    private static int findGraphicsArg(int access, String desc) {
        boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
        int slot = isStatic ? 0 : 1; // skip 'this' for instance methods

        Type[] args = Type.getArgumentTypes(desc);
        for (Type arg : args) {
            String internal = arg.getInternalName();
            if (internal.equals("java/awt/Graphics")
                    || internal.equals("java/awt/Graphics2D")
                    || internal.equals("sun/java2d/SunGraphics2D")) {
                return slot;
            }
            slot += arg.getSize(); // double/long = 2 slots, everything else = 1
        }
        return -1; // no Graphics parameter found
    }

    private static boolean isReturnOpcode(int op) {
        return op == Opcodes.RETURN  || op == Opcodes.IRETURN
                || op == Opcodes.LRETURN || op == Opcodes.FRETURN
                || op == Opcodes.DRETURN || op == Opcodes.ARETURN;
    }

    // =========================================================
    // SCORING
    // =========================================================

    private static int score(byte[] bytes) {
        boolean[] implementsImageProducer = {false};
        boolean[] hasIntArrayField        = {false};
        boolean[] hasDrawImage            = {false};
        int[]     graphicsMethodCount     = {0};

        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                if (interfaces != null)
                    for (String itf : interfaces)
                        if ("java/awt/image/ImageProducer".equals(itf))
                            implementsImageProducer[0] = true;
            }

            @Override
            public FieldVisitor visitField(int access, String name, String desc,
                                           String signature, Object value) {
                if ("[I".equals(desc)) hasIntArrayField[0] = true;
                return super.visitField(access, name, desc, signature, value);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                if (desc.contains("Ljava/awt/Graphics;")) graphicsMethodCount[0]++;
                return new MethodVisitor(Opcodes.ASM9,
                        super.visitMethod(access, name, desc, signature, exceptions)) {
                    @Override
                    public void visitMethodInsn(int op, String owner, String mname,
                                                String mdesc, boolean itf) {
                        if (owner.equals("java/awt/Graphics") && mname.equals("drawImage"))
                            hasDrawImage[0] = true;
                        super.visitMethodInsn(op, owner, mname, mdesc, itf);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG);

        int s = 0;
        if (implementsImageProducer[0]) s += 35;
        if (hasIntArrayField[0])        s += 20;
        if (graphicsMethodCount[0] > 0) s += 20;
        if (hasDrawImage[0])            s += 30;
        return s;
    }
}