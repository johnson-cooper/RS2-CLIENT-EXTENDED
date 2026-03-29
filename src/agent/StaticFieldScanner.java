package agent;

import org.objectweb.asm.*;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Two-phase approach:
 *
 * PHASE 1 (ASM): Scan the bytecode of class `an` to find which static fields
 * it reads during method `e(III)I`. These are the fields that store projected
 * screen coordinates. We log field owner+name+desc for every GETSTATIC in `an.e`.
 *
 * PHASE 2 (Reflection): After the game has been running for 2 seconds, read
 * the actual runtime values of those static int/int[] fields and log them.
 * Fields whose values fall in screen-pixel range are our projection arrays.
 *
 * This tells us EXACTLY where RS2 stores its projected tile coordinates
 * without needing to intercept any method calls.
 */
public class StaticFieldScanner implements ClassFileTransformer {

    // Fields read by an.e — populated during transform
    private static final List<String> fieldsInAnE = Collections.synchronizedList(new ArrayList<>());
    // owner -> [name, desc] pairs
    private static final ConcurrentHashMap<String, List<String[]>> fieldMap = new ConcurrentHashMap<>();

    private static volatile boolean scanned = false;
    private static Instrumentation savedInst;

    public static void install(Instrumentation inst) {
        savedInst = inst;
        inst.addTransformer(new StaticFieldScanner(), true);
        System.out.println("[SFS] installed — scanning an.e for GETSTATIC");

        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (c.getName().equals("an")) {
                try {
                    inst.retransformClasses(c);
                    System.out.println("[SFS] retransformed an");
                } catch (Exception e) {
                    System.out.println("[SFS] retransform failed: " + e);
                }
                break;
            }
        }

        // After 3 seconds, read the actual field values via reflection
        Thread t = new Thread(() -> {
            try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
            readFieldValues(inst);
        }, "SFS-reader");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public byte[] transform(ClassLoader loader, String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain pd, byte[] bytes) {
        if (!className.equals("an")) return null;
        if (scanned) return null;
        scanned = true;

        System.out.println("[SFS] scanning an bytecode for GETSTATIC in e(III)I ...");

        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String sig, String[] ex) {
                if (!name.equals("e") || !desc.equals("(III)I")) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitFieldInsn(int opcode, String owner,
                                               String fname, String fdesc) {
                        if (opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC) {
                            String op = opcode == Opcodes.GETSTATIC ? "GET" : "PUT";
                            String entry = op + " " + owner + "." + fname + " : " + fdesc;
                            if (!fieldsInAnE.contains(entry)) {
                                fieldsInAnE.add(entry);
                                fieldMap.computeIfAbsent(owner, k -> new ArrayList<>())
                                        .add(new String[]{fname, fdesc, op});
                                System.out.println("[SFS] an.e " + op + "STATIC: "
                                        + owner + "." + fname + " " + fdesc);
                            }
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        System.out.println("[SFS] found " + fieldsInAnE.size() + " static field accesses in an.e");
        return null; // don't modify bytecode
    }

    private static void readFieldValues(Instrumentation inst) {
        System.out.println("[SFS] --- reading runtime field values ---");

        int MAX_X = 1920, MAX_Y = 1009;

        for (Class<?> cls : inst.getAllLoadedClasses()) {
            List<String[]> fields = fieldMap.get(cls.getName());
            if (fields == null) continue;

            for (String[] f : fields) {
                String fname = f[0], fdesc = f[1];
                try {
                    Field field = cls.getDeclaredField(fname);
                    field.setAccessible(true);
                    Object val = field.get(null); // static field

                    if (fdesc.equals("I")) {
                        int v = (Integer) val;
                        boolean inX = v >= 0 && v <= MAX_X;
                        boolean inY = v >= 0 && v <= MAX_Y;
                        if (inX || inY) {
                            System.out.println("[SFS] SCREEN-RANGE int: "
                                    + cls.getName() + "." + fname
                                    + " = " + v
                                    + (inY ? " IN_Y" : " X_ONLY"));
                        }
                    } else if (fdesc.equals("[I")) {
                        int[] arr = (int[]) val;
                        if (arr == null) continue;
                        // Sample first 20 values
                        int inRangeCount = 0;
                        int sample0 = arr.length > 0 ? arr[0] : -1;
                        int sample1 = arr.length > 1 ? arr[1] : -1;
                        for (int i = 0; i < Math.min(arr.length, 200); i++) {
                            if (arr[i] >= 0 && arr[i] <= MAX_X) inRangeCount++;
                        }
                        if (inRangeCount > 5) {
                            System.out.println("[SFS] SCREEN-RANGE int[]: "
                                    + cls.getName() + "." + fname
                                    + " len=" + arr.length
                                    + " inRange=" + inRangeCount + "/200"
                                    + " [0]=" + sample0 + " [1]=" + sample1);
                        }
                    } else {
                        // Other types — just log the class
                        System.out.println("[SFS] field: "
                                + cls.getName() + "." + fname
                                + " : " + fdesc
                                + " = " + val);
                    }
                } catch (Exception e) {
                    System.out.println("[SFS] can't read " + cls.getName()
                            + "." + fname + ": " + e.getMessage());
                }
            }
        }
        System.out.println("[SFS] --- done ---");
    }
}