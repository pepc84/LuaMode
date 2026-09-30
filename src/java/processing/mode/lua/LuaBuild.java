package processing.mode.lua;

import processing.app.Sketch;
import processing.app.SketchCode;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Orchestrates a single build+run of a Lua sketch inside the Processing IDE.
 *
 * Responsibilities (only):
 *   1. Assemble the combined source (header + sketch tabs).
 *   2. Write it to a temp file.
 *   3. Locate luamode-runner.
 *   4. Exec luamode-runner and collect output.
 *   5. Delegate error mapping to {@link LuaErrorMapper}.
 *
 * Why luamode-runner instead of raw LuaJIT?
 *   libprocessing's C FFI ({@code processing_surface_create}) requires an
 *   existing native window handle — it cannot create windows on its own.
 *   Window creation goes through glfw::Runner internally in Rust.  The runner
 *   binary uses that same App builder path, so it is a first-class Processing
 *   sketch that happens to be driven by Lua.  No LuaJIT FFI gymnastics needed.
 *
 * Error output from luamode-runner follows the standard Lua format:
 *   {@code <filename>:<line>: <message>}
 * {@link LuaErrorMapper} maps those line numbers back to the correct sketch tab.
 */
public final class LuaBuild {

    // ── Fields ────────────────────────────────────────────────────────────

    private final Sketch sketch;
    private final File   modeFolder;

    // Set by buildSource(), read by run() and LuaErrorMapper
    private int   headerLines;
    private int[] tabStartLines;

    // ── Construction ──────────────────────────────────────────────────────

    public LuaBuild(Sketch sketch, File modeFolder) {
        this.sketch     = sketch;
        this.modeFolder = modeFolder;
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Build and run the sketch via luamode-runner. Blocks until the runner exits.
     *
     * @return list of problems; empty on clean exit
     */
    public List<LuaProblem> run() throws IOException, InterruptedException {
        String source = buildSource();
        Path   tmp    = writeTempFile(source);

        String runner = resolveRunner();
        if (runner == null) {
            return List.of(new LuaProblem(0, 0,
                "luamode-runner not found.  Build it with `cargo build --release` " +
                "inside LuaMode/runner/ and copy the binary into " +
                "mode/runner/<platform>/luamode-runner.",
                LuaProblem.Severity.ERROR));
        }

        ProcessBuilder pb = new ProcessBuilder(runner, tmp.toAbsolutePath().toString());
        pb.directory(sketch.getFolder());   // relative paths (loadImage, saveFrame) resolve in the sketch

        Process proc = pb.start();

        // Processing's console shows whatever goes to System.out / System.err,
        // so forward the runner's output there line by line as it arrives:
        // print() lands in the console like it does in Java mode. stderr is
        // also kept for mapping errors back to tab/line. Separate threads so
        // a full pipe on one stream can't block the other.
        Thread drainOut = pump(proc.getInputStream(), System.out, null, "lua-stdout");
        StringBuilder stderrBuf = new StringBuilder();
        Thread drainErr = pump(proc.getErrorStream(), System.err, stderrBuf, "lua-stderr");

        try {
            proc.waitFor();
        } catch (InterruptedException ie) {
            // Stop pressed: close the sketch window
            proc.destroy();
            if (!proc.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) proc.destroyForcibly();
            Thread.currentThread().interrupt();
            throw ie;
        }

        drainOut.join(500);
        drainErr.join(500);

        if (proc.exitValue() == 0) return List.of();

        return LuaErrorMapper.map(stderrBuf.toString(), headerLines, tabStartLines);
    }

    private static Thread pump(java.io.InputStream in, java.io.PrintStream out,
                               StringBuilder keep, String name) {
        Thread t = new Thread(() -> {
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.println(line);
                    if (keep != null) synchronized (keep) { keep.append(line).append('\n'); }
                }
            } catch (IOException ignored) {}
        }, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    // ── Source assembly ───────────────────────────────────────────────────

    /**
     * Assembles the combined Lua source: generated header + all sketch tabs.
     *
     * The generated header calls {@code setup()} and {@code draw()} once
     * registered — the runner binary registers all Processing globals before
     * loading this file, so the header only needs to be a thin shim that
     * calls P.inject(_ENV) equivalently (the runner already did that).
     *
     * For luamode-runner the header is minimal: the runner pre-registers all
     * Processing globals, so the "header" just needs to document itself and
     * not accidentally shadow anything.
     */
    private String buildSource() {
        String header = ideHeader();
        headerLines   = countLines(header);

        SketchCode[] codes = sketch.getCode();
        tabStartLines      = new int[codes.length];

        StringBuilder sb = new StringBuilder(header);

        // Track the running line count so we don't re-scan the whole buffer
        // for each tab (avoids O(n²) for many-tab sketches).
        int runningLines = headerLines;

        for (int i = 0; i < codes.length; i++) {
            tabStartLines[i] = runningLines;
            String prog = codes[i].getProgram();
            if (prog == null) prog = "";
            sb.append(prog);
            if (!prog.endsWith("\n")) sb.append('\n');
            runningLines += countLines(prog) + (prog.endsWith("\n") ? 0 : 1);
        }

        return sb.toString();
    }

    /**
     * Minimal generated header for luamode-runner.
     *
     * The runner binary pre-registers all Processing globals before loading
     * the sketch, so no require() calls are needed.  This header just marks
     * the generated boundary for LuaErrorMapper and provides the size()
     * override that stores width/height in Lua globals.
     */
    private static String ideHeader() {
        return
            "-- [LuaMode generated, do not edit]\n" +
            "-- All Processing globals are pre-registered by luamode-runner.\n" +
            "-- [end generated header]\n";
    }

    // ── Runner location ───────────────────────────────────────────────────

    /**
     * Looks for luamode-runner in priority order:
     *   1. The binary extracted from the JAR by {@link LuaMode} at startup
     *      (the normal installed path — works for end users out of the box).
     *   2. {@code <modeFolder>/mode/runner/<platform>/luamode-runner}
     *      (standard installed layout from the dist ZIP; {@code modeFolder} is the
     *      mode root, e.g. {@code ~/sketchbook/modes/LuaMode/}).
     *   3. {@code <modeFolder>/runner/target/release/luamode-runner}
     *      (dev build — {@code cargo build --release} inside {@code LuaMode/runner/},
     *      where {@code modeFolder} is the project root).
     *
     * Returns the absolute path string, or null if not found anywhere.
     */
    private String resolveRunner() {
        String exe = System.getProperty("os.name", "").toLowerCase().contains("win")
                     ? "luamode-runner.exe" : "luamode-runner";

        // 1. Extracted from JAR by LuaMode on startup
        File extracted = LuaMode.getRunnerExecutable();
        if (extracted != null && extracted.exists() && extracted.canExecute()) {
            return extracted.getAbsolutePath();
        }

        // 2. Installed in mode/runner/<platform>/ inside the mode root
        //    (standard layout from dist ZIP: LuaMode/mode/runner/<platform>/luamode-runner)
        String platform = LuaMode.detectPlatform();
        File installed = new File(modeFolder, "mode/runner/" + platform + "/" + exe);
        if (installed.exists() && installed.canExecute()) {
            return installed.getAbsolutePath();
        }

        // 3. Dev build — modeFolder is the project root; runner/ is a direct child
        //    (gradle buildRunner copies to mode/runner/<platform>/ but cargo output lands here)
        File devBuild = new File(modeFolder, "runner/target/release/" + exe);
        if (devBuild.exists() && devBuild.canExecute()) {
            return devBuild.getAbsolutePath();
        }

        return null;
    }

    // ── Utilities ─────────────────────────────────────────────────────────

    private static Path writeTempFile(String source) throws IOException {
        Path tmp = Files.createTempFile("processing_lua_", ".lua");
        tmp.toFile().deleteOnExit();
        Files.writeString(tmp, source, StandardCharsets.UTF_8);
        return tmp;
    }

    /** Package-private hook for tests. */
    static int testCountLines(String s) { return countLines(s); }

    private static int countLines(String s) {
        if (s == null || s.isEmpty()) return 0;
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') n++;
        }
        return n;
    }
}
