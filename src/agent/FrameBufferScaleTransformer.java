package agent;

import org.objectweb.asm.*;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public class FrameBufferScaleTransformer implements ClassFileTransformer {

    private final String explicitTarget;
    private volatile String selectedTarget;
    // Also hook the applet base class to clear overlay on login screen
    private static final String APPLET_BASE = "as";
    private static volatile boolean appletHooked = false;

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

        // Hook the applet base class paint() to clear overlay on login screen
        if (className.equals(APPLET_BASE) && !appletHooked) {
            appletHooked = true;
            return hookAppletPaint(bytes);
        }

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

    /**
     * Hook as.paint(Graphics) to call Hooks.onPaint(Graphics).
     * This fires on every AWT repaint including the login screen,
     * giving us a chance to clear stale overlay pixels.
     */
    private byte[] hookAppletPaint(byte[] bytes) {
        try {
            ClassReader cr = new ClassReader(bytes);
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);

            cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc,
                                                 String sig, String[] ex) {
                    MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                    // Hook paint(Graphics)
                    if (name.equals("paint") && desc.equals("(Ljava/awt/Graphics;)V")) {
                        System.out.println("[Hook] hooked as.paint(Graphics)");
                        return new org.objectweb.asm.commons.AdviceAdapter(
                                Opcodes.ASM9, mv, access, name, desc) {
                            @Override
                            protected void onMethodEnter() {
                                // Pass Graphics arg to our handler
                                mv.visitVarInsn(ALOAD, 1);
                                mv.visitMethodInsn(INVOKESTATIC,
                                        "agent/Hooks", "onPaint",
                                        "(Ljava/awt/Graphics;)V", false);
                            }
                        };
                    }
                    return mv;
                }
            }, ClassReader.EXPAND_FRAMES);

            return cw.toByteArray();
        } catch (Exception e) {
            System.out.println("[Hook] failed to hook as.paint: " + e);
            return null;
        }
    }

    private static java.util.Set<String> findRenderMethods(byte[] bytes) {
        java.util.Set<String> methods = new java.util.HashSet<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
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

    private byte[] inject(byte[] bytes) {
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
                if (!renderMethods.contains(key)) return mv;

                System.out.println("[Inject] hooking render method: " + name + desc);
                int graphicsArgIndex = findGraphicsArg(access, desc);

                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        if (graphicsArgIndex >= 0) {
                            mv.visitVarInsn(Opcodes.ALOAD, graphicsArgIndex);
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "agent/ScaleHelper", "registerGraphics",
                                    "(Ljava/awt/Graphics;)V", false);
                            System.out.println("[Inject] registerGraphics(arg "
                                    + graphicsArgIndex + ") in " + name);
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
                                    "agent/ScaleHelper", "applyScale", "()V", false);
                        }

                        super.visitMethodInsn(opcode, owner, mname, mdesc, itf);

                        if (isDrawImage) {
                            // Pass 'this' (the at instance) so Hooks can check
                            // framebuffer dimensions and only draw on the 512x334 game viewport
                            mv.visitVarInsn(Opcodes.ALOAD, 0); // push 'this'
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "agent/Hooks", "drawOverlayDirect",
                                    "(Ljava/lang/Object;)V", false);
                            System.out.println("[Inject] scale before + overlay after drawImage in " + name);
                        }
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        if (isReturnOpcode(opcode)) {
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "agent/ScaleHelper", "clearGraphics", "()V", false);
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);

        return cw.toByteArray();
    }

    private static int findGraphicsArg(int access, String desc) {
        boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
        int slot = isStatic ? 0 : 1;
        Type[] args = Type.getArgumentTypes(desc);
        for (Type arg : args) {
            String internal = arg.getInternalName();
            if (internal.equals("java/awt/Graphics")
                    || internal.equals("java/awt/Graphics2D")
                    || internal.equals("sun/java2d/SunGraphics2D")) {
                return slot;
            }
            slot += arg.getSize();
        }
        return -1;
    }

    private static boolean isReturnOpcode(int op) {
        return op == Opcodes.RETURN  || op == Opcodes.IRETURN
                || op == Opcodes.LRETURN || op == Opcodes.FRETURN
                || op == Opcodes.DRETURN || op == Opcodes.ARETURN;
    }

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