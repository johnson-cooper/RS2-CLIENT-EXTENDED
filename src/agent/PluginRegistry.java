package agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Central registry for all agent plugins.
 *
 * Plugins register themselves here at startup. ClientSidebar reads this
 * registry to build its icon strip — it has no knowledge of specific plugins.
 *
 * Registration order determines sidebar order.
 */
public final class PluginRegistry {

    private static final List<Plugin> plugins = new ArrayList<>();

    private PluginRegistry() {}

    /** Register a plugin. Call from AgentMain or the plugin's static initializer. */
    public static void register(Plugin plugin) {
        plugins.add(plugin);
        System.out.println("[PluginRegistry] registered: " + plugin.getName());
    }

    /** Returns an unmodifiable view of all registered plugins in registration order. */
    public static List<Plugin> getAll() {
        return Collections.unmodifiableList(plugins);
    }

    /** Call onLoad() on all registered plugins. */
    public static void loadAll() {
        for (Plugin p : plugins) {
            try {
                p.onLoad();
            } catch (Throwable t) {
                System.out.println("[PluginRegistry] onLoad failed for " + p.getName() + ": " + t);
            }
        }
    }
}