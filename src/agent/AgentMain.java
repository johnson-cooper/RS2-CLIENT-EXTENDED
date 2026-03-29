package agent;

import java.lang.instrument.Instrumentation;

public class AgentMain {

    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("[Agent] loaded");

        inst.addTransformer(new FrameBufferScaleTransformer(), true);
        CoordinateHook.install(inst);

        WindowStretch.start();
        Bootstrap.init();
        TileMarkerInput.install();
        ClientSidebar.startWatcher();

        System.out.println("[Agent] ready");
    }
}