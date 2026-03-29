package agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.security.ProtectionDomain;

public class CoordinateHook implements ClassFileTransformer {

    private static Instrumentation savedInst;
    private static volatile boolean initialized = false;

    public static void install(Instrumentation inst) {
        savedInst = inst;
        inst.addTransformer(new CoordinateHook(), false);
        System.out.println("[CoordHook] installed");
        startRetryThread(inst);
    }

    private static synchronized void tryInit(Instrumentation inst) {
        if (initialized) return;

        Class<?> clientClass = null, aJClass = null, zClass = null;
        for (Class<?> c : inst.getAllLoadedClasses()) {
            switch (c.getName()) {
                case "Client": clientClass = c; break;
                case "aJ":     aJClass     = c; break;
                case "Z":      zClass      = c; break;
            }
        }
        if (clientClass == null || aJClass == null || zClass == null) return;

        Object clientInstance = findClientInstance(inst, clientClass);
        if (clientInstance == null) return;

        TileProjector.init(clientInstance, clientClass, aJClass, zClass);
        initialized = true;
        System.out.println("[CoordHook] TileProjector initialized");
    }

    private static Object findClientInstance(Instrumentation inst, Class<?> clientClass) {
        for (Class<?> c : inst.getAllLoadedClasses()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() == clientClass &&
                        java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    try {
                        Object val = f.get(null);
                        if (val != null) {
                            System.out.println("[CoordHook] Client instance: "
                                    + c.getName() + "." + f.getName());
                            return val;
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
        return null;
    }

    private static void startRetryThread(Instrumentation inst) {
        Thread t = new Thread(() -> {
            for (int i = 0; i < 240 && !initialized; i++) {
                try { Thread.sleep(500); } catch (InterruptedException e) { return; }
                tryInit(inst);
            }
            if (!initialized) System.out.println("[CoordHook] gave up after 120s");
        }, "CoordHook-retry");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public byte[] transform(ClassLoader loader, String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain pd, byte[] bytes) {
        // Trigger a retry when relevant classes load
        if (!initialized && savedInst != null &&
                (className.equals("Client") || className.equals("aJ") || className.equals("Z"))) {
            Thread t = new Thread(() -> {
                try { Thread.sleep(100); } catch (InterruptedException e) {}
                tryInit(savedInst);
            });
            t.setDaemon(true);
            t.start();
        }
        return null;
    }
}