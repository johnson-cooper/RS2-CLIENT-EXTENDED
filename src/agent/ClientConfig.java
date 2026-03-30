package agent;

public final class ClientConfig {

    // Native RS2 canvas dimensions — never change these
    public static final int BASE_WIDTH  = 765;
    public static final int BASE_HEIGHT = 503;

    // Live inner size — updated by WindowStretch on every resize event
    private static volatile int innerWidth  = BASE_WIDTH;
    private static volatile int innerHeight = BASE_HEIGHT;

    private ClientConfig() {}

    /** Called by WindowStretch whenever the frame is resized. */
    public static void setInnerSize(int w, int h) {
        if (w > 0) innerWidth  = w;
        if (h > 0) innerHeight = h;
    }

    /** Scale-X to apply to the 765-wide framebuffer. */
    public static double getScaleX() {
        return (double) innerWidth / BASE_WIDTH;
    }

    /** Scale-Y to apply to the 503-tall framebuffer. */
    public static double getScaleY() {
        return (double) innerHeight / BASE_HEIGHT;
    }

    // Kept so ScaleHelper compiles without changes
    public static int getTargetWidth()  { return innerWidth; }
    public static int getTargetHeight() { return innerHeight; }
}