package processing.mode.lua;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalTime;

/**
 * Appends what happens on each Run to ~/.processing/luamode/run.log, so a run
 * that fails before the sketch starts still leaves a trace outside the IDE.
 */
final class RunLog {
    private static final File FILE =
        new File(System.getProperty("user.home"), ".processing/luamode/run.log");

    private RunLog() {}

    static synchronized void log(String msg) {
        try {
            FILE.getParentFile().mkdirs();
            try (FileWriter w = new FileWriter(FILE, true)) {
                w.write(LocalTime.now().withNano(0) + "  " + msg + "\n");
            }
        } catch (Exception ignored) {}
    }

    static void log(String msg, Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        log(msg + "\n" + sw);
    }
}
