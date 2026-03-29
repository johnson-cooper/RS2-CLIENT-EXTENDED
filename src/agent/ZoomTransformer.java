package agent;

import org.objectweb.asm.*;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * Patches the RS2 scene renderer classes (aN, Z) to replace the hardcoded
 * focal length shift with a call to ZoomController.getFocal().
 *
 * Original bytecode pattern (projection only — has idiv after ishl):
 *   bipush 9
 *   ishl
 *   iload_N        <- depth variable
 *   idiv
 *
 * Replaced with:
 *   invokestatic ZoomController.getFocal()I
 *   imul
 *   iload_N        <- depth variable (unchanged)
 *   idiv
 *
 * Texture/lighting << 9 patterns are NOT replaced because they are followed
 * by iadd/iand/imul rather than idiv, so our lookahead check skips them safely.
 */
public class ZoomTransformer implements ClassFileTransformer {

    private static final String ZOOM_OWNER  = "agent/ZoomController";
    private static final String ZOOM_METHOD = "getFocal";
    private static final String ZOOM_DESC   = "()I";

    @Override
    public byte[] transform(ClassLoader loader, String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain pd, byte[] bytes) {
        if (className == null) return null;
        // Patch both the scene renderer (aN) and the geometry rasterizer (Z)
        if (!className.equals("aN") && !className.equals("Z") && !className.equals("aJ")) return null;

        try {
            ClassReader cr = new ClassReader(bytes);
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);

            cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc,
                                                 String sig, String[] ex) {
                    MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);

                    // Skip aN visibility precompute: public static void a(IIII[I)V
                    // Built once at load time with hardcoded mR=256/mS=167.
                    // Patching this corrupts the static visibility lookup table.
                    if (className.equals("aN")
                            && name.equals("a")
                            && desc.equals("(IIII[I)V")) {
                        System.out.println("[ZoomTransformer] skipping aN visibility precompute");
                        return mv;
                    }

                    // Skip Z.w() — near-clip special case using hardcoded /50 depth.
                    // This only fires when a vertex is behind the camera (depth < 50).
                    // It uses mh + (worldX << 9) / 50 which we cannot patch without
                    // also changing the hardcoded near-plane depth of 50.
                    if (className.equals("Z") && name.equals("w")) {
                        System.out.println("[ZoomTransformer] skipping Z.w near-clip");
                        return mv;
                    }

                    // Z.c and Z.a: only patch focal length in screen projections.
                    // bW/bX scaling is now handled centrally in aJ.a() via WorldParamScaler.
                    // This avoids double-scaling since Z.w() also calls aJ.a() with bW/bX.

                    // aJ.a(19 params): scale world coords at method entry
                    if (className.equals("aJ")) {
                        return new WorldParamScaler(mv, name, desc);
                    }

                    return new FocalLengthPatcher(mv, className, name);
                }
            }, ClassReader.EXPAND_FRAMES);

            System.out.println("[ZoomTransformer] patched " + className);
            return cw.toByteArray();
        } catch (Exception e) {
            System.out.println("[ZoomTransformer] failed on " + className + ": " + e);
            return null;
        }
    }

    // AjARedirectPatcher removed - world coord scaling for aJ.a() is handled
    // by patching aJ itself (see transform() - aJ is now also a target class)

    /**
     * Patches Z.c() to scale bW and bX writes by getFocal()/512.
     * Detects: GETSTATIC Z.bW/bX, ILOAD n, ILOAD m, IASTORE
     * Inserts: invokestatic scaledWorld() between value load and IASTORE.
     */
    private static class WorldScalePatcher extends MethodVisitor {
        private static final String ZOOM_OWNER  = "agent/ZoomController";

        // State: we buffer a pending IASTORE when we see GETSTATIC for bW or bX
        private boolean pendingScale = false;
        private int scaleCount = 0;
        private final String className;
        private final String methodName;

        WorldScalePatcher(MethodVisitor mv, String cls, String method) {
            super(Opcodes.ASM9, mv);
            this.className  = cls;
            this.methodName = method;
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String desc) {
            // Detect GETSTATIC for bW or bX arrays in class Z
            if (opcode == Opcodes.GETSTATIC
                    && owner.equals("Z")
                    && (name.equals("bW") || name.equals("bX"))
                    && desc.equals("[I")) {
                pendingScale = true;
            } else {
                pendingScale = false;
            }
            super.visitFieldInsn(opcode, owner, name, desc);
        }

        @Override
        public void visitInsn(int opcode) {
            if (opcode == Opcodes.IASTORE && pendingScale) {
                // Insert scaledWorld() call before the array store
                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                        ZOOM_OWNER, "scaledWorld", "(I)I", false);
                scaleCount++;
                pendingScale = false;
            } else {
                pendingScale = false;
            }
            super.visitInsn(opcode);
        }

        @Override
        public void visitEnd() {
            super.visitEnd();
            if (scaleCount > 0) {
                System.out.println("[ZoomTransformer]   " + className + "."
                        + methodName + ": " + scaleCount + " world coord(s) scaled");
            }
        }

        // Reset pending on any other instruction that intervenes
        @Override public void visitVarInsn(int op, int v) { super.visitVarInsn(op, v); }
        @Override public void visitMethodInsn(int op, String o, String n, String d, boolean i) {
            pendingScale = false; super.visitMethodInsn(op, o, n, d, i); }
        @Override public void visitJumpInsn(int op, Label l) {
            pendingScale = false; super.visitJumpInsn(op, l); }
        @Override public void visitIntInsn(int op, int operand) {
            pendingScale = false; super.visitIntInsn(op, operand); }
        @Override public void visitLdcInsn(Object v) {
            pendingScale = false; super.visitLdcInsn(v); }
    }

    /**
     * MethodVisitor that buffers instructions and replaces:
     *   bipush 9 + ishl  ->  invokestatic getFocal + imul
     * but ONLY when the ishl is followed by an iload + idiv (projection pattern).
     *
     * We use a 3-instruction lookahead buffer:
     *   slot0 = current candidate (bipush 9)
     *   slot1 = ishl
     *   slot2 = iload (depth)
     *   on seeing idiv: flush the replacement
     */
    private static class FocalLengthPatcher extends MethodVisitor {

        private final String className;
        private final String methodName;
        private int patchCount = 0;

        // Pending instruction slots for lookahead
        // We track: bipush-9, ishl, iload(depth)
        private static final int NONE    = 0;
        private static final int BIPUSH9 = 1;
        private static final int ISHL    = 2;
        private static final int ILOAD   = 3;

        private int state = NONE;
        private int pendingIload = -1; // local variable index for depth iload

        FocalLengthPatcher(MethodVisitor mv, String cls, String method) {
            super(Opcodes.ASM9, mv);
            this.className  = cls;
            this.methodName = method;
        }

        // When we can't match the pattern, flush buffered instructions normally
        private void flushBipush9() {
            if (state >= BIPUSH9) {
                super.visitIntInsn(Opcodes.BIPUSH, 9);
            }
            if (state >= ISHL) {
                super.visitInsn(Opcodes.ISHL);
            }
            if (state >= ILOAD) {
                emitIload(pendingIload);
            }
            state = NONE;
            pendingIload = -1;
        }

        private void emitIload(int var) {
            // visitVarInsn(ILOAD, n) works for all n including 0-3
            super.visitVarInsn(Opcodes.ILOAD, var);
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            if (opcode == Opcodes.BIPUSH && operand == 9 && state == NONE) {
                // Start buffering — might be projection shift
                state = BIPUSH9;
                return;
            }
            flushBipush9();
            super.visitIntInsn(opcode, operand);
        }

        @Override
        public void visitInsn(int opcode) {
            if (opcode == Opcodes.ISHL && state == BIPUSH9) {
                state = ISHL;
                return;
            }
            if (opcode == Opcodes.IDIV && state == ILOAD) {
                // Pattern complete: bipush9 + ishl + iload(depth) + idiv
                // Emit: invokestatic getFocal, imul, iload(depth), idiv
                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                        ZOOM_OWNER, ZOOM_METHOD, ZOOM_DESC, false);
                super.visitInsn(Opcodes.IMUL);
                emitIload(pendingIload);
                super.visitInsn(Opcodes.IDIV);
                patchCount++;
                state = NONE;
                pendingIload = -1;
                return;
            }
            // Any other instruction — flush buffer and emit normally
            flushBipush9();
            super.visitInsn(opcode);
        }

        @Override
        public void visitVarInsn(int opcode, int var) {
            if (opcode == Opcodes.ILOAD && state == ISHL) {
                // Got the depth load — buffer it
                state = ILOAD;
                pendingIload = var;
                return;
            }
            flushBipush9();
            super.visitVarInsn(opcode, var);
        }

        @Override
        public void visitEnd() {
            flushBipush9();
            super.visitEnd();
            if (patchCount > 0) {
                System.out.println("[ZoomTransformer]   " + className + "."
                        + methodName + ": " + patchCount + " projection(s) patched");
            }
        }

        // Flush on any other instruction type to avoid corruption
        @Override public void visitFieldInsn(int op, String o, String n, String d) {
            flushBipush9(); super.visitFieldInsn(op, o, n, d); }
        @Override public void visitMethodInsn(int op, String o, String n, String d, boolean i) {
            flushBipush9(); super.visitMethodInsn(op, o, n, d, i); }
        @Override public void visitJumpInsn(int op, Label l) {
            flushBipush9(); super.visitJumpInsn(op, l); }
        @Override public void visitLabel(Label l) {
            flushBipush9(); super.visitLabel(l); }
        @Override public void visitLdcInsn(Object v) {
            flushBipush9(); super.visitLdcInsn(v); }
        @Override public void visitIincInsn(int v, int i) {
            flushBipush9(); super.visitIincInsn(v, i); }
        @Override public void visitTypeInsn(int op, String t) {
            flushBipush9(); super.visitTypeInsn(op, t); }
    }

    /**
     * Patches aJ.a(IIIIIIIIIIIIIIIIIII)V at method entry.
     * Local variables 9-14 are worldX[3] and worldY[3].
     * Scales them by getFocal()/512 so UV gradients are focal-independent.
     *
     * Injected at method start:
     *   var9  = scaledWorld(var9);
     *   var10 = scaledWorld(var10);
     *   var11 = scaledWorld(var11);
     *   var12 = scaledWorld(var12);
     *   var13 = scaledWorld(var13);
     *   var14 = scaledWorld(var14);
     */
    private static class WorldParamScaler extends MethodVisitor {
        private static final String ZOOM_OWNER = "agent/ZoomController";
        private final boolean active;

        WorldParamScaler(MethodVisitor mv, String name, String desc) {
            super(Opcodes.ASM9, mv);
            // Only activate for the 19-param textured triangle method
            this.active = name.equals("a") && desc.equals("(IIIIIIIIIIIIIIIIIII)V");
        }

        @Override
        public void visitCode() {
            super.visitCode();
            if (!active) return;
            // Scale local vars 9 through 14 (worldX[3] and worldY[3])
            for (int var = 9; var <= 14; var++) {
                super.visitVarInsn(Opcodes.ILOAD, var);
                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                        ZOOM_OWNER, "scaledWorld", "(I)I", false);
                super.visitVarInsn(Opcodes.ISTORE, var);
            }
        }
    }

}