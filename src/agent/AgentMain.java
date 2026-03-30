package agent;

import agent.plugins.tilemarker.*;

import java.lang.instrument.Instrumentation;

public class AgentMain {

    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("[Agent] loaded");

        inst.addTransformer(new FrameBufferScaleTransformer(), true);
        inst.addTransformer(new ZoomTransformer(), true);
        CoordinateHook.install(inst);

        // Register plugins — order determines sidebar order
        PluginRegistry.register(new TileMarkerPlugin());

        WindowStretch.start();
        Bootstrap.init();
        ZoomController.install();
        ClientSidebar.startWatcher();

        // onLoad() is called after all infrastructure is up
        PluginRegistry.loadAll();

        System.out.println("[Agent] ready");
    }
}