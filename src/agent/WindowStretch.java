package agent;

import java.awt.*;
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

            f.setResizable(true);

            // Set initial size — ClientSidebar.layoutFrame handles all subsequent
            // resize events and keeps ClientConfig up to date immediately.
            // We do NOT add a componentResized listener here to avoid conflicts.
            updateInnerSize(f);
        }
    }

    static void updateInnerSize(Frame f) {
        Insets ins = f.getInsets();
        int w = f.getWidth()  - ins.left - ins.right;
        int h = f.getHeight() - ins.top  - ins.bottom;
        if (w > 0 && h > 0) {
            ClientConfig.setInnerSize(w, h);
            System.out.println("[WindowStretch] inner size -> " + w + "x" + h);
        }
    }
}