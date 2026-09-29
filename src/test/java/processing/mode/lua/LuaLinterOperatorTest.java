package processing.mode.lua;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the Java/JS operator-mistake checks and plain-Lua API hints
 * in {@link LuaLinter}.
 *
 * Uses the package-private hooks {@code testScanOperatorMistakes},
 * {@code testScanApiHints}, and {@code stripCommentAndStrings} — no native
 * tree-sitter library required.
 */
class LuaLinterOperatorTest {

    private static final int[] TAB0 = {0};

    private List<LuaProblem> scan(String source) {
        return LuaLinter.testScanOperatorMistakes(source, TAB0);
    }

    private List<LuaProblem> hints(String source) {
        return LuaLinter.testScanApiHints(source, TAB0);
    }

    // ── != ────────────────────────────────────────────────────────────────────

    @Test
    void notEqual_flagged() {
        List<LuaProblem> ps = scan("if x != y then end\n");
        assertEquals(1, ps.size());
        assertTrue(ps.get(0).getMessage().contains("~="));
        assertFalse(ps.get(0).isError(), "Operator mistake is a WARNING");
    }

    @Test
    void luaNotEqual_notFlagged() {
        assertTrue(scan("if x ~= y then end\n").isEmpty());
    }

    @Test
    void notEqualInString_notFlagged() {
        assertTrue(scan("local s = \"a!=b\"\n").isEmpty());
    }

    @Test
    void notEqualInComment_notFlagged() {
        assertTrue(scan("-- use != for inequality in C\n").isEmpty());
    }

    @Test
    void notEqualInSingleQuoteString_notFlagged() {
        assertTrue(scan("local s = 'a!=b'\n").isEmpty());
    }

    // ── && ────────────────────────────────────────────────────────────────────

    @Test
    void andOperator_flagged() {
        List<LuaProblem> ps = scan("if a && b then end\n");
        assertEquals(1, ps.size());
        assertTrue(ps.get(0).getMessage().contains("and"));
    }

    @Test
    void luaAnd_notFlagged() {
        assertTrue(scan("if a and b then end\n").isEmpty());
    }

    @Test
    void andInString_notFlagged() {
        assertTrue(scan("local s = \"a&&b\"\n").isEmpty());
    }

    @Test
    void andInComment_notFlagged() {
        assertTrue(scan("-- use && in JavaScript\n").isEmpty());
    }

    // ── || ────────────────────────────────────────────────────────────────────

    @Test
    void orOperator_flagged() {
        List<LuaProblem> ps = scan("if a || b then end\n");
        assertEquals(1, ps.size());
        assertTrue(ps.get(0).getMessage().contains("or"));
    }

    @Test
    void luaOr_notFlagged() {
        assertTrue(scan("if a or b then end\n").isEmpty());
    }

    @Test
    void orInString_notFlagged() {
        assertTrue(scan("local s = 'a||b'\n").isEmpty());
    }

    // ── ! (unary not) ─────────────────────────────────────────────────────────

    @Test
    void bangNot_flagged() {
        List<LuaProblem> ps = scan("if !x then end\n");
        assertEquals(1, ps.size());
        assertTrue(ps.get(0).getMessage().contains("not"));
    }

    @Test
    void luaNot_notFlagged() {
        assertTrue(scan("if not x then end\n").isEmpty());
    }

    @Test
    void bangEqualNotDouble_flagged_once() {
        List<LuaProblem> ps = scan("if x != y then end\n");
        long neqCount  = ps.stream().filter(p -> p.getMessage().contains("~=")).count();
        // match the '!' warning itself; the '!=' warning's text also contains "not"
        long bangCount = ps.stream().filter(p -> p.getMessage().contains("Use 'not'")).count();
        assertEquals(1, neqCount,  "!= should produce exactly one ~= warning");
        assertEquals(0, bangCount, "! in != should NOT also produce a 'not' warning");
    }

    @Test
    void bangInString_notFlagged() {
        assertTrue(scan("local s = \"!important\"\n").isEmpty());
    }

    @Test
    void bangInComment_notFlagged() {
        assertTrue(scan("-- !x means 'not x' in C\n").isEmpty());
    }

    // ── Multiple operators on the same line ───────────────────────────────────

    @Test
    void multipleOperators_allFlagged() {
        List<LuaProblem> ps = scan("if a && b || c != d then end\n");
        assertTrue(ps.stream().anyMatch(p -> p.getMessage().contains("and")));
        assertTrue(ps.stream().anyMatch(p -> p.getMessage().contains("or")));
        assertTrue(ps.stream().anyMatch(p -> p.getMessage().contains("~=")));
    }

    // ── Multi-line source ─────────────────────────────────────────────────────

    @Test
    void multiLine_correctLineNumbers() {
        String src =
            "local x = 1\n" +
            "if x != 2 then\n" +
            "end\n";
        List<LuaProblem> ps = scan(src);
        assertEquals(1, ps.size());
        assertEquals(1, ps.get(0).getLineNumber(), "Warning should be on line 1 (0-based)");
        assertEquals(0, ps.get(0).getTabIndex());
    }

    @Test
    void cleanSource_noWarnings() {
        String src =
            "function setup()\n" +
            "  size(640, 480)\n" +
            "end\n" +
            "function draw()\n" +
            "  if mousePressed and not keyPressed then\n" +
            "    background(0)\n" +
            "  end\n" +
            "end\n";
        assertTrue(scan(src).isEmpty());
    }

    // ── stripCommentAndStrings ────────────────────────────────────────────────

    @Test
    void strip_commentRemoved() {
        String result = LuaLinter.stripCommentAndStrings("x = 1 -- comment != here");
        assertFalse(result.contains("!="));
        assertTrue(result.startsWith("x = 1 "));
    }

    @Test
    void strip_doubleQuoteContentsBlank() {
        String result = LuaLinter.stripCommentAndStrings("local s = \"hello != world\"");
        assertFalse(result.contains("!="));
        assertTrue(result.contains("\""));
    }

    @Test
    void strip_singleQuoteContentsBlank() {
        String result = LuaLinter.stripCommentAndStrings("local s = 'a&&b'");
        assertFalse(result.contains("&&"));
    }

    @Test
    void strip_escapedQuoteInsideString() {
        String result = LuaLinter.stripCommentAndStrings("local s = \"say \\\"hi\\\"\"");
        assertFalse(result.contains("hi"));
    }

    @Test
    void strip_codeBeforeStringKept() {
        String result = LuaLinter.stripCommentAndStrings("x != y and s = \"!=\"");
        assertTrue(result.contains("!="));
    }

    @Test
    void strip_nothingToStrip() {
        String line = "local x = 1 + 2";
        assertEquals(line, LuaLinter.stripCommentAndStrings(line));
    }

    // ── API hints: math.random / math.pi / os.exit ───────────────────────────

    @Test
    void mathRandom_flagged() {
        List<LuaProblem> ps = hints("local x = math.random(10)\n");
        assertEquals(1, ps.size());
        assertTrue(ps.get(0).getMessage().contains("random()"));
    }

    @Test
    void processingRandom_notFlagged() {
        assertTrue(hints("local x = random(10)\n").isEmpty());
    }

    @Test
    void mathRandom_inComment_notFlagged() {
        assertTrue(hints("-- use math.random in plain Lua\n").isEmpty());
    }

    @Test
    void mathRandom_inString_notFlagged() {
        assertTrue(hints("local s = \"math.random\"\n").isEmpty());
    }

    @Test
    void mathPi_flagged() {
        List<LuaProblem> ps = hints("local r = math.pi * 2\n");
        assertEquals(1, ps.size());
        assertTrue(ps.get(0).getMessage().contains("PI"));
    }

    @Test
    void processingPI_notFlagged() {
        assertTrue(hints("local r = PI * 2\n").isEmpty());
    }

    @Test
    void osExit_flagged() {
        List<LuaProblem> ps = hints("os.exit(0)\n");
        assertEquals(1, ps.size());
        assertTrue(ps.get(0).getMessage().contains("exit()"));
    }

    @Test
    void processingExit_notFlagged() {
        assertTrue(hints("exit()\n").isEmpty());
    }

    @Test
    void osExit_inComment_notFlagged() {
        assertTrue(hints("-- call os.exit to quit\n").isEmpty());
    }

    @Test
    void apiHints_correctLineNumber() {
        String src = "local x = 1\nlocal r = math.random(5)\n";
        List<LuaProblem> ps = hints(src);
        assertEquals(1, ps.size());
        assertEquals(1, ps.get(0).getLineNumber());
    }

    @Test
    void apiHints_multipleOnOneLine() {
        // Both math.pi and math.random on one line — each fires once
        List<LuaProblem> ps = hints("local x = math.random() * math.pi\n");
        assertTrue(ps.stream().anyMatch(p -> p.getMessage().contains("random()")));
        assertTrue(ps.stream().anyMatch(p -> p.getMessage().contains("PI")));
    }
}
