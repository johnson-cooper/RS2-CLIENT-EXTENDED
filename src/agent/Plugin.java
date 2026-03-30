package agent;

import javax.swing.JPanel;

/**
 * Contract for all agent plugins.
 *
 * A plugin provides:
 *  - A sidebar icon (single Unicode character or short string)
 *  - A display name shown in the expanded panel header
 *  - A settings panel built on demand
 *  - Optional lifecycle hooks called by the agent
 */
public interface Plugin {

    /** Short icon shown in the sidebar icon strip (e.g. "⬡", "🔍", "+"). */
    String getIcon();

    /** Full display name shown in the expanded settings panel header. */
    String getName();

    /**
     * Build and return the settings panel shown when the icon is clicked.
     * Called each time the panel is expanded — can return a fresh panel each time.
     */
    JPanel buildPanel();

    /**
     * Called once when the agent finishes loading.
     * Use this to install event listeners, start threads, etc.
     * Default: no-op.
     */
    default void onLoad() {}

    /**
     * Called once when the game client is closing.
     * Default: no-op.
     */
    default void onUnload() {}
}