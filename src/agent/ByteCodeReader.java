package agent;

import org.objectweb.asm.*;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/**
 * Prints bytecode of projection candidates in class aN.
 * Target methods:
 *   aN.f(IIII)V   — 4 int args void
 *   aN.g(IIII)V   — 4 int args void  
 *   aN.p(III)V    — 3 int args void
 *   aN.i(III)I    — 3 int args returns int
 *
 * Also prints ALL methods in aN that have (III)I or (IIII)I signatures
 * in case the projection is under a different letter.
 *
 * Does NOT modify bytecode.
 */
public class ByteCodeReader implements ClassFileTransformer {

    public static void install(Instrumentation inst) {
        inst.addTransformer(new ByteCodeReader(), true);
        System.out.println("[BCR] installed — targeting aN");
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (c.getName().equals("aN")) {
                try {
                    inst.retransformClasses(c);
                    System.out.println("[BCR] retransformed aN");
                } catch (Exception e) {
                    System.out.println("[BCR] retransform failed: " + e);
                }
                break;
            }
        }
        // Also try Z
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (c.getName().equals("Z")) {
                try {
                    inst.retransformClasses(c);
                    System.out.println("[BCR] retransformed Z");
                } catch (Exception e) {
                    System.out.println("[BCR] retransform failed Z: " + e);
                }
                break;
            }
        }
    }

    @Override
    public byte[] transform(ClassLoader loader, String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain pd, byte[] bytes) {

        boolean isaN = className.equals("aN");
        boolean isZ  = className.equals("Z");
        if (!isaN && !isZ) return null;

        System.out.println("[BCR] scanning: " + className);

        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String sig, String[] ex) {
                // For aN: print f,g,p,i and anything returning I with <=5 int args
                // For Z: print c(III)I and anything similar
                boolean print = false;
                if (isaN) {
                    print = (name.equals("f") && desc.equals("(IIII)V"))
                         || (name.equals("g") && desc.equals("(IIII)V"))
                         || (name.equals("p") && desc.equals("(III)V"))
                         || (name.equals("i") && desc.equals("(III)I"))
                         || desc.equals("(III)I")
                         || desc.equals("(IIII)I");
                }
                if (isZ) {
                    print = desc.equals("(III)I") || desc.equals("(IIII)I")
                         || desc.equals("(III)V");
                }
                if (!print) return null;

                boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
                System.out.println("\n[BCR] === " + className + "." + name + desc
                        + (isStatic ? " STATIC" : " INSTANCE") + " ===");

                return new MethodVisitor(Opcodes.ASM9) {
                    int line = 0;
                    @Override public void visitInsn(int op) {
                        System.out.println("[BCR]  " + line++ + "  " + opName(op));
                    }
                    @Override public void visitVarInsn(int op, int var) {
                        System.out.println("[BCR]  " + line++ + "  " + opName(op) + " " + var);
                    }
                    @Override public void visitIntInsn(int op, int operand) {
                        System.out.println("[BCR]  " + line++ + "  " + opName(op) + " " + operand);
                    }
                    @Override public void visitLdcInsn(Object cst) {
                        System.out.println("[BCR]  " + line++ + "  LDC " + cst);
                    }
                    @Override public void visitFieldInsn(int op, String owner, String fname, String fdesc) {
                        String opn = op==Opcodes.GETSTATIC?"GETSTATIC":op==Opcodes.PUTSTATIC?"PUTSTATIC"
                                   :op==Opcodes.GETFIELD?"GETFIELD":"PUTFIELD";
                        System.out.println("[BCR]  " + line++ + "  " + opn + " " + owner + "." + fname + ":" + fdesc);
                    }
                    @Override public void visitMethodInsn(int op, String owner, String mname, String mdesc, boolean itf) {
                        System.out.println("[BCR]  " + line++ + "  INVOKE " + owner + "." + mname + mdesc);
                    }
                    @Override public void visitJumpInsn(int op, Label label) {
                        System.out.println("[BCR]  " + line++ + "  " + opName(op) + " L");
                    }
                    @Override public void visitIincInsn(int var, int inc) {
                        System.out.println("[BCR]  " + line++ + "  IINC " + var + " +" + inc);
                    }
                    @Override public void visitLabel(Label l) {
                        System.out.println("[BCR] L:");
                    }
                    @Override public void visitTypeInsn(int op, String type) {
                        System.out.println("[BCR]  " + line++ + "  " + opName(op) + " " + type);
                    }

                    String opName(int op) {
                        switch(op) {
                            case Opcodes.ILOAD: return "ILOAD";
                            case Opcodes.ISTORE: return "ISTORE";
                            case Opcodes.ALOAD: return "ALOAD";
                            case Opcodes.ASTORE: return "ASTORE";
                            case Opcodes.LLOAD: return "LLOAD";
                            case Opcodes.LSTORE: return "LSTORE";
                            case Opcodes.IALOAD: return "IALOAD";
                            case Opcodes.IASTORE: return "IASTORE";
                            case Opcodes.BALOAD: return "BALOAD";
                            case Opcodes.IMUL: return "IMUL";
                            case Opcodes.IDIV: return "IDIV";
                            case Opcodes.IADD: return "IADD";
                            case Opcodes.ISUB: return "ISUB";
                            case Opcodes.ISHR: return "ISHR";
                            case Opcodes.IUSHR: return "IUSHR";
                            case Opcodes.ISHL: return "ISHL";
                            case Opcodes.IAND: return "IAND";
                            case Opcodes.IOR: return "IOR";
                            case Opcodes.IXOR: return "IXOR";
                            case Opcodes.IREM: return "IREM";
                            case Opcodes.INEG: return "INEG";
                            case Opcodes.LMUL: return "LMUL";
                            case Opcodes.LADD: return "LADD";
                            case Opcodes.LSUB: return "LSUB";
                            case Opcodes.LSHR: return "LSHR";
                            case Opcodes.LUSHR: return "LUSHR";
                            case Opcodes.I2L: return "I2L";
                            case Opcodes.L2I: return "L2I";
                            case Opcodes.IRETURN: return "IRETURN";
                            case Opcodes.RETURN: return "RETURN";
                            case Opcodes.LRETURN: return "LRETURN";
                            case Opcodes.DUP: return "DUP";
                            case Opcodes.DUP2: return "DUP2";
                            case Opcodes.POP: return "POP";
                            case Opcodes.POP2: return "POP2";
                            case Opcodes.SWAP: return "SWAP";
                            case Opcodes.ICONST_0: return "ICONST_0";
                            case Opcodes.ICONST_1: return "ICONST_1";
                            case Opcodes.ICONST_2: return "ICONST_2";
                            case Opcodes.ICONST_3: return "ICONST_3";
                            case Opcodes.ICONST_4: return "ICONST_4";
                            case Opcodes.ICONST_5: return "ICONST_5";
                            case Opcodes.ICONST_M1: return "ICONST_M1";
                            case Opcodes.LCONST_0: return "LCONST_0";
                            case Opcodes.LCONST_1: return "LCONST_1";
                            case Opcodes.IF_ICMPGE: return "IF_ICMPGE";
                            case Opcodes.IF_ICMPGT: return "IF_ICMPGT";
                            case Opcodes.IF_ICMPLE: return "IF_ICMPLE";
                            case Opcodes.IF_ICMPLT: return "IF_ICMPLT";
                            case Opcodes.IF_ICMPEQ: return "IF_ICMPEQ";
                            case Opcodes.IF_ICMPNE: return "IF_ICMPNE";
                            case Opcodes.IFGE: return "IFGE";
                            case Opcodes.IFGT: return "IFGT";
                            case Opcodes.IFLE: return "IFLE";
                            case Opcodes.IFLT: return "IFLT";
                            case Opcodes.IFEQ: return "IFEQ";
                            case Opcodes.IFNE: return "IFNE";
                            case Opcodes.IFNULL: return "IFNULL";
                            case Opcodes.IFNONNULL: return "IFNONNULL";
                            case Opcodes.GOTO: return "GOTO";
                            case Opcodes.ARRAYLENGTH: return "ARRAYLENGTH";
                            default: return "OP("+op+")";
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG);

        return null; // no modification
    }
}