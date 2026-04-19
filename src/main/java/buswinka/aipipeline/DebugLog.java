package buswinka.aipipeline;

import ij.IJ;

/**
 * Debug logging — all calls compile to nothing in production builds
 * because BuildConfig.DEBUG is a static final false (dead-code eliminated by JIT).
 * Enable by building with: mvn install -Pdebug-build
 */
public final class DebugLog {

    public static void log(String tag, String msg) {
        if (BuildConfig.DEBUG) {
            String line = "[DEBUG:" + tag + "] " + msg;
            IJ.log(line);
            System.err.println(line);
        }
    }

    public static void log(String tag, String format, Object... args) {
        if (BuildConfig.DEBUG) {
            String line = "[DEBUG:" + tag + "] " + String.format(format, args);
            IJ.log(line);
            System.err.println(line);
        }
    }

    private DebugLog() {}
}