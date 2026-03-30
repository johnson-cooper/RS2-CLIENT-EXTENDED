package agent;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public class ViewportAutoTransformer implements ClassFileTransformer {

    private static final int SCORE_THRESHOLD = 50;
    private static volatile String chosenTarget;

    @Override
    public byte[] transform(ClassLoader loader,
                            String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) {

        if (className == null || classfileBuffer == null) return null;
        if (isJdkClass(className) || className.startsWith("agent/")) return null;

        try {
            // If already selected and not this class → skip
            if (chosenTarget != null && !className.equals(chosenTarget)) {
                return null;
            }

            // If already selected → transform it
            if (chosenTarget != null && className.equals(chosenTarget)) {
                System.out.println("[ViewportHook] transforming renderer: " + className);
                return transformTarget(classfileBuffer);
            }

            // Discovery phase
            CandidateInfo info = analyze(classfileBuffer);

            if (info.score >= SCORE_THRESHOLD) {
                System.out.println("[ViewportCandidate] " + className
                        + " score=" + info.score
                        + " super=" + info.superName
                        + " run=" + info.hasRun
                        + " paint=" + info.hasPaint
                        + " update=" + info.hasUpdate);

                chosenTarget = className;
                System.out.println("[ViewportTarget] chosen=" + chosenTarget + " — transforming now");

                return transformTarget(classfileBuffer);
            }

            return null;

        } catch (Throwable t) {
            System.out.println("[ViewportHook] failed for " + className + ": " + t);
            t.printStackTrace();
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // INJECTION
    // -------------------------------------------------------------------------

    private byte[] transformTarget(byte[] bytes) {
        ClassReader cr = new ClassReader(bytes);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {

                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);

                // FPS hook
                if (name.equals("run") && desc.equals("()V")) {
                    System.out.println("[Hook] run()");
                    return new AdviceAdapter(Opcodes.ASM9, mv, access, name, desc) {
                        @Override
                        protected void onMethodEnter() {
                            visitMethodInsn(INVOKESTATIC,
                                    "agent/Hooks",
                                    "onFrame",
                                    "()V",
                                    false);
                        }
                    };
                }

                // 🔥 FIND REAL RENDER METHOD (drawImage path)
                if (desc.contains("Ljava/awt/Graphics;")) {
                    System.out.println("[Scan] Graphics method: " + name + desc);
                }

                // 🎯 HOOK FINAL RENDER METHOD
                // Known RS2 pattern: (I, Graphics, I)
                if (desc.equals("(ILjava/awt/Graphics;I)V")) {
                    System.out.println("[Inject] hooking render method: " + name + desc);

                    return new AdviceAdapter(Opcodes.ASM9, mv, access, name, desc) {
                        @Override
                        protected void onMethodExit(int opcode) {
                            // Graphics is arg index 1
                            loadArg(1);

                            visitMethodInsn(INVOKESTATIC,
                                    "agent/Hooks",
                                    "drawOverlay",
                                    "(Ljava/awt/Graphics;)V",
                                    false);
                        }
                    };
                }

                return mv;
            }
        };

        cr.accept(cv, ClassReader.EXPAND_FRAMES);
        return cw.toByteArray();
    }

    // -------------------------------------------------------------------------
    // SCORING
    // -------------------------------------------------------------------------

    private CandidateInfo analyze(byte[] bytes) {
        CandidateInfo info = new CandidateInfo();

        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {

            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                info.superName = superName;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {

                if (name.equals("run")    && desc.equals("()V"))                      info.hasRun    = true;
                if (name.equals("paint")  && desc.equals("(Ljava/awt/Graphics;)V"))   info.hasPaint  = true;
                if (name.equals("update") && desc.equals("(Ljava/awt/Graphics;)V"))   info.hasUpdate = true;

                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitIntInsn(int op, int operand) {
                        if (operand == 765) info.has765 = true;
                        if (operand == 503) info.has503 = true;
                    }
                    @Override public void visitLdcInsn(Object value) {
                        if (Integer.valueOf(765).equals(value)) info.has765 = true;
                        if (Integer.valueOf(503).equals(value)) info.has503 = true;
                    }
                    @Override public void visitMethodInsn(int op, String owner,
                                                          String mname, String mdesc, boolean itf) {
                        if ("java/awt/Graphics".equals(owner) && "drawImage".equals(mname))
                            info.hasDrawImage = true;
                        if ("java/awt/Component".equals(owner) && "setSize".equals(mname))
                            info.hasSetSize = true;
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);

        int score = 0;

        if ("java/awt/Applet".equals(info.superName)
                || "java/awt/Canvas".equals(info.superName)
                || "java/awt/Panel".equals(info.superName)
                || "java/awt/Frame".equals(info.superName))  score += 30;

        if (info.hasRun)       score += 20;
        if (info.hasPaint)     score += 15;
        if (info.hasUpdate)    score += 15;
        if (info.hasDrawImage) score += 15;
        if (info.hasSetSize)   score += 10;
        if (info.has765)       score += 10;
        if (info.has503)       score += 10;

        info.score = score;
        return info;
    }

    private boolean isJdkClass(String className) {
        return className.startsWith("java/")
                || className.startsWith("javax/")
                || className.startsWith("sun/")
                || className.startsWith("com/sun/")
                || className.startsWith("jdk/");
    }

    private static final class CandidateInfo {
        String superName;
        int score;
        boolean hasRun, hasPaint, hasUpdate, hasDrawImage, hasSetSize, has765, has503;
    }
}