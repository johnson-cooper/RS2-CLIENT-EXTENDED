package agent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public class LoggingTransformer implements ClassFileTransformer {

    @Override
    public byte[] transform(ClassLoader loader,
                             String className,
                             Class<?> clazz,
                             ProtectionDomain pd,
                             byte[] bytes) {

        if (className == null) return null;

        // Log only interesting packages to avoid spam
        if (className.contains("client")
                || className.contains("game")
                || className.contains("java/awt")) {

            System.out.println("[Load] " + className);
        }

        return null; // no modification
    }
}