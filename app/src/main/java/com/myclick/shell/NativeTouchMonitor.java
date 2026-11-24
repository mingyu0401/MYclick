package com.myclick.shell;

public final class NativeTouchMonitor {

    public interface Listener {
        void onTouchStart(int rawX, int rawY);
    }

    private NativeTouchMonitor() {
    }

    public static boolean load(String absolutePath) {
        return runLoad(absolutePath);
    }

    private static boolean runLoad(String absolutePath) {
        try {
            System.load(absolutePath);
            return true;
        } catch (Throwable error) {
            System.err.println("MYclick native touch load failed: " + error);
            return false;
        }
    }

    public static native long nativeStart(String path, Listener listener);

    public static native boolean nativeRunning(long handle);

    public static native void nativeStop(long handle);
}
