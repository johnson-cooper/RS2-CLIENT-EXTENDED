package agent;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Auto-discovers and loads all Plugin implementations found under
 * the agent.plugins package in the agent jar.
 *
 * No registration, no service files — just implement Plugin, put your
 * class under agent/plugins/**, and it will be picked up automatically.
 */
public final class PluginRegistry {

    private static final List<Plugin> plugins = new ArrayList<>();

    private PluginRegistry() {}

    /**
     * Scan the jar that loaded this class for all classes under
     * agent.plugins that implement Plugin, instantiate them, and register.
     *
     * Call once from AgentMain.premain().
     */
    public static void discoverAll() {
        try {
            // Find the jar file this class was loaded from
            URL location = PluginRegistry.class.getProtectionDomain()
                    .getCodeSource().getLocation();
            File jarFile = new File(location.toURI());

            if (!jarFile.getName().endsWith(".jar")) {
                // Running from class files (IDE) - fall back to classpath scan
                discoverFromClasspath();
                return;
            }

            ClassLoader cl = PluginRegistry.class.getClassLoader();

            try (JarFile jar = new JarFile(jarFile)) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();

                    // Only look at .class files under agent/plugins/
                    if (!name.startsWith("agent/plugins/") || !name.endsWith(".class")) continue;
                    // Skip inner classes
                    if (name.contains("$")) continue;

                    String className = name.replace('/', '.').replace(".class", "");
                    tryRegister(className, cl);
                }
            }

            System.out.println("[PluginRegistry] discovered " + plugins.size() + " plugin(s)");

        } catch (Exception e) {
            System.out.println("[PluginRegistry] discovery failed: " + e);
        }
    }

    /**
     * Fallback for IDE / exploded class directories.
     * Scans the agent/plugins directory on the filesystem.
     */
    private static void discoverFromClasspath() {
        try {
            URL pluginsUrl = PluginRegistry.class.getClassLoader()
                    .getResource("agent/plugins");
            if (pluginsUrl == null) return;

            File pluginsDir = new File(pluginsUrl.toURI());
            ClassLoader cl = PluginRegistry.class.getClassLoader();
            scanDir(pluginsDir, "agent.plugins", cl);

            System.out.println("[PluginRegistry] discovered " + plugins.size()
                    + " plugin(s) from classpath");
        } catch (Exception e) {
            System.out.println("[PluginRegistry] classpath scan failed: " + e);
        }
    }

    private static void scanDir(File dir, String pkg, ClassLoader cl) {
        if (!dir.exists() || !dir.isDirectory()) return;
        for (File f : dir.listFiles()) {
            if (f.isDirectory()) {
                scanDir(f, pkg + "." + f.getName(), cl);
            } else if (f.getName().endsWith(".class") && !f.getName().contains("$")) {
                String className = pkg + "." + f.getName().replace(".class", "");
                tryRegister(className, cl);
            }
        }
    }

    private static void tryRegister(String className, ClassLoader cl) {
        try {
            Class<?> cls = cl.loadClass(className);
            if (!Plugin.class.isAssignableFrom(cls)) return;
            if (cls.isInterface() || java.lang.reflect.Modifier.isAbstract(cls.getModifiers())) return;

            Plugin p = (Plugin) cls.getDeclaredConstructor().newInstance();
            plugins.add(p);
            System.out.println("[PluginRegistry] registered: " + p.getName());
        } catch (Exception ignored) {}
    }

    /** Manually register a plugin (optional, for programmatic use). */
    public static void register(Plugin plugin) {
        plugins.add(plugin);
        System.out.println("[PluginRegistry] registered: " + plugin.getName());
    }

    public static List<Plugin> getAll() {
        return Collections.unmodifiableList(plugins);
    }

    public static void loadAll() {
        for (Plugin p : plugins) {
            try {
                p.onLoad();
            } catch (Throwable t) {
                System.out.println("[PluginRegistry] onLoad failed for "
                        + p.getName() + ": " + t);
            }
        }
    }
}