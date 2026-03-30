package agent;

import java.awt.AWTEvent;
import java.awt.Toolkit;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;

public class Bootstrap {

    public static void init() {
        Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
            if (event instanceof MouseWheelEvent) {
                ZoomController.handleWheel((MouseWheelEvent) event);
            } else if (event instanceof MouseEvent) {
                InputFixer.handle((MouseEvent) event);
            }
        },
            AWTEvent.MOUSE_EVENT_MASK
          | AWTEvent.MOUSE_MOTION_EVENT_MASK
          | AWTEvent.MOUSE_WHEEL_EVENT_MASK
        );

        System.out.println("[InputFixer] installed");
    }
}