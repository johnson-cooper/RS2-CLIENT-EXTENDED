package agent;

import java.awt.AWTEvent;
import java.awt.Toolkit;
import java.awt.event.MouseEvent;

public class Bootstrap {

    public static void init() {
        Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
            if (event instanceof MouseEvent) {
                InputFixer.handle((MouseEvent) event);
            }
        },
            AWTEvent.MOUSE_EVENT_MASK          // clicks
          | AWTEvent.MOUSE_MOTION_EVENT_MASK   // movement + drag
          | AWTEvent.MOUSE_WHEEL_EVENT_MASK    // scroll wheel
        );

        System.out.println("[InputFixer] installed");
    }
}