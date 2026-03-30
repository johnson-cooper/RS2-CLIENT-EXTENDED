package agent;

import java.lang.instrument.Instrumentation;

public class AgentMain {

    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("[Agent] loaded");

        inst.addTransformer(new FrameBufferScaleTransformer(), true);
        inst.addTransformer(new ZoomTransformer(), true);
        CoordinateHook.install(inst);

        // Auto-discover everything under agent.plugins.** that implements Plugin
        PluginRegistry.discoverAll();

        WindowStretch.start();
        Bootstrap.init();
        ZoomController.install();
        ClientSidebar.startWatcher();

        PluginRegistry.loadAll();

        System.out.println("[Agent] ready");
    }
}