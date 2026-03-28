package agent;

import java.lang.instrument.Instrumentation;

public class AgentMain {

    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("[Agent] loaded");

        inst.addTransformer(new FrameBufferScaleTransformer(), true);

        WindowStretch.start();

        // Activate mouse coordinate remapping AFTER transformers are registered
        Bootstrap.init();

        System.out.println("[Agent] resizable stretch active");
    }
}