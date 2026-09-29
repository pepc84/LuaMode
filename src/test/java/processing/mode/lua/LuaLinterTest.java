package processing.mode.lua;

import ch.usi.si.seart.treesitter.Node;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LuaLinter}.
 *
 * <p>These tests require the native {@code libtree-sitter-lua} grammar to be
 * loadable at runtime.  When the library is absent (e.g. on a CI machine that
 * only runs the Java build without native libraries), the whole class is skipped
 * gracefully via the {@link BeforeAll} guard.</p>
 *
 * <p>To run locally, build or download the grammar and place the result in
 * {@code lib/<platform>/libtree-sitter-lua.{so,dylib,dll}}.  Set the system
 * property {@code java.library.path} to that directory, or run via Gradle
 * (which sets it automatically via the {@code test} task JVM args when the
 * library is present).</p>
 */
class LuaLinterTest {

    // Single-tab: tab 0 starts at line 0 (no header in linter tests — we feed
    // raw sketch source rather than the combined header+sketch source).
    private static final int[] SINGLE_TAB = {0};

    private static boolean nativeAvailable = false;
    private static TsLuaEngine engine;

    @BeforeAll
    static void loadNative() {
        try {
            engine = TsLuaEngine.get();
            // Attempt a minimal parse to verify the native library is loaded and
            // the Lua grammar is registered.
            try (TsLuaEngine.ParseResult pr = engine.parse("-- ok")) {
                assertNotNull(pr, "TsLuaEngine.parse returned null");
            }
            nativeAvailable = true;
        } catch (UnsatisfiedLinkError | ExceptionInInitializerError | Exception ignored) {
            // Native library not available — all tests in this class will be skipped.
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void assumeNative() {
        org.junit.jupiter.api.Assumptions.assumeTrue(nativeAvailable,
            "libtree-sitter-lua not available — skipping linter integration test");
    }

    private List<LuaProblem> lint(String source) {
        LuaLinter linter = new LuaLinter();
        try (TsLuaEngine.ParseResult pr = engine.parse(source)) {
            assertNotNull(pr);
            return linter.lint(source, pr.root, SINGLE_TAB);
        }
    }

    // ── Clean source ──────────────────────────────────────────────────────────

    @Test
    void cleanSketch_noProblems() {
        assumeNative();
        List<LuaProblem> ps = lint(
            "function setup()\n" +
            "  size(640, 480)\n" +
            "end\n" +
            "function draw()\n" +
            "  background(20)\n" +
            "end\n"
        );
        assertTrue(ps.isEmpty(), "Clean sketch should produce no lint problems");
    }

    // ── Syntax error detection ─────────────────────────────────────────────────

    @Test
    void syntaxError_reported() {
        assumeNative();
        // Missing 'end' — tree-sitter marks this as an error
        List<LuaProblem> ps = lint(
            "function draw()\n" +
            "  background(0\n"   // missing closing paren + end
        );
        assertFalse(ps.isEmpty(), "Missing 'end' should produce a syntax error");
        assertTrue(ps.stream().anyMatch(LuaProblem::isError),
            "At least one problem should be ERROR severity");
    }

    @Test
    void multipleErrors_allReported() {
        assumeNative();
        String src =
            "local x =\n" +       // incomplete assignment
            "local y =\n";        // another incomplete assignment
        List<LuaProblem> ps = lint(src);
        // We expect at least one error — tree-sitter may merge adjacent errors
        // into a single ERROR node, so we don't assert an exact count.
        assertFalse(ps.isEmpty(), "Broken source should report at least one error");
    }

    // ── Framework call warnings ────────────────────────────────────────────────

    @Test
    void explicitSetupCall_warnsUser() {
        assumeNative();
        List<LuaProblem> ps = lint(
            "function setup() size(400,400) end\n" +
            "function draw()\n" +
            "  setup()   -- explicitly calling setup — wrong!\n" +
            "end\n"
        );
        assertTrue(ps.stream().anyMatch(p -> !p.isError() && p.getMessage().contains("setup")),
            "Explicit setup() call should produce a warning");
    }

    @Test
    void explicitDrawCall_warnsUser() {
        assumeNative();
        List<LuaProblem> ps = lint(
            "function setup() draw() end\n" +
            "function draw() background(0) end\n"
        );
        assertTrue(ps.stream().anyMatch(p -> !p.isError() && p.getMessage().contains("draw")),
            "Explicit draw() call should produce a warning");
    }

    @Test
    void indirectCall_notWarned() {
        assumeNative();
        // A local variable named 'setup' being called — not a direct identifier call
        List<LuaProblem> ps = lint(
            "local setup = function() end\n" +
            "function draw()\n" +
            "  -- calling obj:draw() should NOT warn\n" +
            "  local obj = {}\n" +
            "  function obj:draw() end\n" +
            "  obj:draw()\n" +
            "end\n"
        );
        // Method call obj:draw() should not produce the framework-call warning
        long warnings = ps.stream()
            .filter(p -> !p.isError() && p.getMessage().contains("draw"))
            .count();
        assertEquals(0, warnings, "obj:draw() method call should not warn");
    }

    // ── Tab mapping ───────────────────────────────────────────────────────────

    @Test
    void errorLine_mappedToTab0() {
        assumeNative();
        List<LuaProblem> ps = lint("bad syntax ??? here\n");
        assertFalse(ps.isEmpty());
        LuaProblem first = ps.stream().filter(LuaProblem::isError).findFirst().orElseThrow();
        assertEquals(0, first.getTabIndex(), "Single-tab source should always map to tab 0");
    }

    @Test
    void warningLine_correctLine() {
        assumeNative();
        // Warning on line 2 (0-based) — the draw() call
        List<LuaProblem> ps = lint(
            "function setup() end\n" +
            "function draw()\n" +
            "  draw()\n" +       // line 2 (0-based)
            "end\n"
        );
        LuaProblem w = ps.stream()
            .filter(p -> !p.isError() && p.getMessage().contains("draw"))
            .findFirst().orElse(null);
        assertNotNull(w, "Expected a warning for explicit draw() call");
        assertEquals(2, w.getLineNumber(), "draw() call is on line 2 (0-based)");
    }

    // ── Event-callback direct-call warnings ───────────────────────────────────

    @Test
    void explicitMousePressedCall_warnsUser() {
        assumeNative();
        List<LuaProblem> ps = lint(
            "function mousePressed() end\n" +
            "function draw()\n" +
            "  mousePressed()  -- wrong: framework calls this\n" +
            "end\n"
        );
        assertTrue(ps.stream().anyMatch(p -> !p.isError() && p.getMessage().contains("mousePressed")),
            "Direct mousePressed() call should produce a warning");
    }

    @Test
    void explicitKeyPressedCall_warnsUser() {
        assumeNative();
        List<LuaProblem> ps = lint(
            "function keyPressed() end\n" +
            "function draw() keyPressed() end\n"
        );
        assertTrue(ps.stream().anyMatch(p -> !p.isError() && p.getMessage().contains("keyPressed")));
    }

    @Test
    void readingMousePressedBoolean_notWarned() {
        assumeNative();
        List<LuaProblem> ps = lint(
            "function draw()\n" +
            "  if mousePressed then background(0) end\n" +
            "end\n"
        );
        long warnings = ps.stream()
            .filter(p -> !p.isError() && p.getMessage().contains("mousePressed"))
            .count();
        assertEquals(0, warnings, "Reading the mousePressed boolean should not warn");
    }

}
