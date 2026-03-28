package agent;

import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

public final class WindowStretch {

    private static final Map<Frame, Boolean> attached =
            Collections.synchronizedMap(new WeakHashMap<>());

    private WindowStretch() {}

    public static void start() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    attachToNewFrames();
                    Thread.sleep(500);
                } catch (Throwable ignored) {}
            }
        }, "WindowStretch");

        t.setDaemon(true);
        t.start();
        System.out.println("[WindowStretch] running");
    }

    private static void attachToNewFrames() {
        for (Frame f : Frame.getFrames()) {
            if (f == null || !f.isVisible()) continue;
            if (attached.containsKey(f)) continue;

            attached.put(f, Boolean.TRUE);
            System.out.println("[WindowStretch] attached to: " + f.getTitle());

            // Make resizable — this is all we force on the frame itself
            f.setResizable(true);

            // Push the current size into ClientConfig immediately
            updateInnerSize(f);

            // Update ClientConfig on every resize
            f.addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    updateInnerSize(f);
                }
            });
        }
    }

    /**
     * Measures the usable inner area (frame size minus title bar / borders)
     * and tells ClientConfig, which ScaleHelper reads at draw time.
     */
    private static void updateInnerSize(Frame f) {
        Insets ins = f.getInsets();
        int w = f.getWidth()  - ins.left - ins.right;
        int h = f.getHeight() - ins.top  - ins.bottom;

        if (w > 0 && h > 0) {
            ClientConfig.setInnerSize(w, h);
            System.out.println("[WindowStretch] inner size -> " + w + "x" + h);
        }
    }
}