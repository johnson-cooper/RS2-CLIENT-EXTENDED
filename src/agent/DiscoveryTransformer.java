package agent;

import org.objectweb.asm.*;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public class DiscoveryTransformer implements ClassFileTransformer {

    @Override
    public byte[] transform(ClassLoader loader,
                            String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) {
        if (className == null) return null;

        if (className.startsWith("java/")
                || className.startsWith("javax/")
                || className.startsWith("sun/")
                || className.startsWith("com/sun/")
                || className.startsWith("jdk/")
                || className.startsWith("agent/")) {
            return null;
        }

        try {
            ClassReader cr = new ClassReader(classfileBuffer);
            cr.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc,
                                                 String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        private boolean saw765;
                        private boolean saw503;
                        private boolean sawSetSize;
                        private boolean sawDrawImage;
                        private boolean sawGetWidthHeight;

                        @Override
                        public void visitIntInsn(int opcode, int operand) {
                            if (operand == 765) saw765 = true;
                            if (operand == 503) saw503 = true;
                        }

                        @Override
                        public void visitLdcInsn(Object value) {
                            if (value instanceof Integer) {
                                int v = (Integer) value;
                                if (v == 765) saw765 = true;
                                if (v == 503) saw503 = true;
                            }
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String mname, String mdesc, boolean itf) {
                            if (owner.equals("java/awt/Component") && mname.equals("setSize")) sawSetSize = true;
                            if (owner.equals("java/awt/Graphics") && mname.equals("drawImage")) sawDrawImage = true;
                            if (mname.equals("getWidth") || mname.equals("getHeight")) sawGetWidthHeight = true;
                        }

                        @Override
                        public void visitEnd() {
                            if (saw765 || saw503 || sawSetSize || sawDrawImage || sawGetWidthHeight) {
                                System.out.println("[CANDIDATE] " + className + " :: " + name + desc
                                        + "  765=" + saw765
                                        + " 503=" + saw503
                                        + " setSize=" + sawSetSize
                                        + " drawImage=" + sawDrawImage
                                        + " sizeCalls=" + sawGetWidthHeight);
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        } catch (Throwable t) {
            System.out.println("[Discovery] failed for " + className + " : " + t);
        }

        return null;
    }
}