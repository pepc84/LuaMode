package processing.mode.lua;

import processing.app.Base;
import processing.app.Mode;
import processing.app.ui.EditorState;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

/**
 * Processing 4 Mode plugin — Lua Mode.
 *
 * On startup:
 *   1. Extracts lib/<platform>/libjava-tree-sitter.so from the JAR and loads
 *      it (JNI glue + tree-sitter core + Lua grammar, built by native/build.sh).
 *   2. Extracts the bundled {@code luamode-runner} binary from the JAR to
 *      a persistent per-version cache directory so {@link LuaBuild} can find
 *      and invoke it without knowing where the JAR lives.
 *
 * The runner is extracted to:
 *   {@code ~/.processing/luamode/<version>/runner/<platform>/luamode-runner}
 *
 * On subsequent IDE starts the cached binary is reused unless the JAR version
 * has changed (different directory), so startup stays fast.
 */
public class LuaMode extends Mode {

    private static final String VERSION = "0.1.0";

    private static boolean nativeLoaded  = false;
    private static File    cachedRunner  = null;

    public LuaMode(Base base, File folder) {
        super(base, folder);
        preloadTreeSitterNative(folder);
        extractRunner();
        // Warm up the parser pool in the background. Skip it if the native lib
        // didn't load: touching Language.LUA would just throw on another thread.
        if (nativeLoaded) {
            new Thread(() -> {
                try {
                    TsLuaEngine.get();
                } catch (Throwable e) {
                    System.err.println("[LuaMode] tree-sitter warmup failed: " + e);
                }
            }, "luamode-warmup").start();
        }
    }

    // ── tree-sitter native library ────────────────────────────────────────
    //
    // lib/<platform>/libjava-tree-sitter.so is built by native/build.sh. It
    // contains the java-tree-sitter JNI glue, tree-sitter core and the Lua
    // grammar, all in one file, so there is nothing else to load.
    //
    // Catch Throwable: a bad native lib throws UnsatisfiedLinkError (an Error),
    // and letting that escape kills the whole mode.

    private static synchronized void preloadTreeSitterNative(File modeFolder) {
        if (nativeLoaded) return;
        try {
            loadBundledLib("libjava-tree-sitter.so", "java-tree-sitter");
            nativeLoaded = true;
        } catch (Throwable e) {
            System.err.println("[LuaMode] Failed to load tree-sitter native: " + e);
        }
    }

    static boolean isNativeLoaded() {
        return nativeLoaded;
    }

    private static void loadBundledLib(String libFile, String prefix) throws Exception {
        String resource = "lib/" + detectPlatform() + "/" + libFile;
        try (InputStream in = LuaMode.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new Exception("native lib not bundled: " + resource);
            Path tmp = Files.createTempFile("luamode_" + prefix + "_", libFile);
            tmp.toFile().deleteOnExit();
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            System.load(tmp.toAbsolutePath().toString());
        }
    }

    // ── luamode-runner extraction ─────────────────────────────────────────

    /**
     * Extracts luamode-runner from the JAR to a persistent cache directory.
     * Skips extraction if the cached binary already exists (version-keyed path
     * means a new release automatically extracts a fresh copy).
     *
     * Sets {@link #cachedRunner} so {@link LuaBuild} can call
     * {@link LuaMode#getRunnerExecutable()} without re-scanning the JAR.
     */
    private static synchronized void extractRunner() {
        if (cachedRunner != null) return;

        String platform = detectPlatform();
        boolean isWin   = platform.startsWith("windows");
        String  exeName = isWin ? "luamode-runner.exe" : "luamode-runner";
        String  resource = "runner/" + platform + "/" + exeName;

        // Persistent cache: ~/.processing/luamode/<version>/runner/<platform>/
        File cacheDir = new File(
            System.getProperty("user.home"),
            ".processing/luamode/" + VERSION + "/runner/" + platform);
        File dest = new File(cacheDir, exeName);

        if (dest.exists() && dest.canExecute()) {
            cachedRunner = dest;
            return;
        }

        try (InputStream in = LuaMode.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                // Not bundled — dev build or platform not packaged yet; LuaBuild falls back.
                System.err.println("[LuaMode] luamode-runner not bundled for: " + platform);
                return;
            }
            cacheDir.mkdirs();
            Files.copy(in, dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            makeExecutable(dest);
            cachedRunner = dest;
            System.out.println("[LuaMode] Extracted runner → " + dest);
        } catch (Exception e) {
            System.err.println("[LuaMode] Runner extraction failed: " + e.getMessage());
        }
    }

    private static void makeExecutable(File f) {
        // Prefer POSIX permissions; fall back to the cross-platform API.
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(f.toPath(), perms);
        } catch (UnsupportedOperationException ignored) {
            // Windows — File.setExecutable is a no-op but harmless
            f.setExecutable(true, false);
        } catch (Exception e) {
            f.setExecutable(true, false);
        }
    }

    /**
     * Returns the extracted luamode-runner binary, or {@code null} if it has
     * not been extracted yet (only possible if {@link #extractRunner()} has not
     * run, which cannot happen after construction).
     */
    public static File getRunnerExecutable() {
        return cachedRunner;
    }

    // ── Shared platform detection ─────────────────────────────────────────

    static String detectPlatform() {
        String os   = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        String a    = arch.contains("aarch64") || arch.contains("arm64") ? "aarch64" : "x86_64";
        if (os.contains("linux"))   return "linux-"   + a;
        if (os.contains("mac"))     return "macos-"   + a;
        if (os.contains("windows")) return "windows-" + a;
        return "linux-x86_64";
    }

    // ── Import Library menu ───────────────────────────────────────────────
    //
    // Lua sketches don't use Processing libraries, but Editor still builds and
    // re-inserts an Import Library menu on every window focus. Mode's default
    // rebuild walks a library list LuaMode never populates and throws, which
    // takes the whole menu bar down. Give it a disabled placeholder instead.

    @Override
    public void rebuildImportMenu() {
        if (importMenu == null) {
            importMenu = new javax.swing.JMenu("Import Library...");
            javax.swing.JMenuItem none = new javax.swing.JMenuItem("No libraries for Lua sketches");
            none.setEnabled(false);
            importMenu.add(none);
        }
    }

    @Override
    public javax.swing.JMenu getImportMenu() {
        rebuildImportMenu();
        return importMenu;
    }

    // ── Mode identity ─────────────────────────────────────────────────────

    @Override public String getTitle()             { return "Lua"; }
    @Override public String getDefaultExtension() { return "lua"; }
    @Override public String[] getExtensions()     { return new String[]{"lua", "pde"}; }
    @Override public String[] getIgnorable()      { return new String[0]; }

    // ── Editor factory ────────────────────────────────────────────────────

    @Override
    public LuaEditor createEditor(Base base, String path, EditorState state) {
        try {
            return new LuaEditor(base, path, state, this);
        } catch (Exception e) {
            throw new RuntimeException("Could not create LuaEditor", e);
        }
    }

    // ── Examples ──────────────────────────────────────────────────────────

    @Override
    public File[] getExampleCategoryFolders() {
        File examples = new File(getFolder(), "examples");
        return examples.exists() ? new File[]{examples} : new File[0];
    }
}
