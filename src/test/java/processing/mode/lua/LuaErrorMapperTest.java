package processing.mode.lua;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LuaErrorMapper}.
 *
 * These are pure logic tests — no Processing IDE runtime needed.
 */
class LuaErrorMapperTest {

    // 3-line header, single tab (tab 0 starts at combined line 3)
    private static final int   HEADER = 3;
    private static final int[] ONE_TAB = {3};

    // ── Single-tab mapping ────────────────────────────────────────────────

    @Test
    void singleTab_firstLine() {
        // Line 4 in combined source (0-based) = user line 1, tab 0, line 1
        String out = "/tmp/processing_lua_1.lua:4: attempt to index nil";
        List<LuaProblem> ps = LuaErrorMapper.map(out, HEADER, ONE_TAB);

        assertEquals(1, ps.size());
        LuaProblem p = ps.get(0);
        assertEquals(0, p.getTabIndex());
        assertEquals(0, p.getLineNumber());   // 0-based; line 4 → user line 0
        assertEquals("attempt to index nil", p.getMessage());
    }

    @Test
    void singleTab_laterLine() {
        // Line 10 combined (1-based) → 0-based 9 → user line 6 → tab 0, line 6
        String out = "/tmp/processing_lua_1.lua:10: bad argument";
        List<LuaProblem> ps = LuaErrorMapper.map(out, HEADER, ONE_TAB);

        assertEquals(1, ps.size());
        assertEquals(0, ps.get(0).getTabIndex());
        assertEquals(6, ps.get(0).getLineNumber());
    }

    // ── Two-tab mapping ───────────────────────────────────────────────────
    //
    // header = 3 lines
    // tab 0 starts at combined line 3, is 5 lines long
    // tab 1 starts at combined line 8

    private static final int[] TWO_TABS = {3, 8};

    @Test
    void twoTabs_lineInTab0() {
        // Combined line 6 (1-based) → 0-based 5 → user line 2 → tab 0 (starts at 0), line 2
        String out = "/tmp/x.lua:6: oops";
        List<LuaProblem> ps = LuaErrorMapper.map(out, HEADER, TWO_TABS);

        assertEquals(0, ps.get(0).getTabIndex());
        assertEquals(2, ps.get(0).getLineNumber());
    }

    @Test
    void twoTabs_lineInTab1() {
        // Combined line 10 (1-based) → 0-based 9 → user line 6 → tab 1 (user offset 5), line 1
        String out = "/tmp/x.lua:10: type error";
        List<LuaProblem> ps = LuaErrorMapper.map(out, HEADER, TWO_TABS);

        assertEquals(1, ps.get(0).getTabIndex());
        assertEquals(1, ps.get(0).getLineNumber());
    }

    // ── Windows path with drive letter ────────────────────────────────────

    @Test
    void windowsPath() {
        String out = "C:\\tmp\\processing_lua_999.lua:5: nil value";
        List<LuaProblem> ps = LuaErrorMapper.map(out, HEADER, ONE_TAB);

        assertEquals(1, ps.size());
        assertEquals("nil value", ps.get(0).getMessage());
    }

    // ── Multi-line output (traceback) ─────────────────────────────────────

    @Test
    void traceback_onlyFirstLineMatters() {
        String out = "/tmp/x.lua:5: bad value\nstack traceback:\n\t/tmp/x.lua:5: in function 'draw'\n\t[C]: in ?";
        List<LuaProblem> ps = LuaErrorMapper.map(out, HEADER, ONE_TAB);

        // Only the first line is a structured error; the rest are ignored.
        assertEquals(1, ps.size());
        assertEquals("bad value", ps.get(0).getMessage());
    }

    // ── Header error ──────────────────────────────────────────────────────

    @Test
    void errorInHeader_surfacedAtTab0Line0() {
        // Line 2 (1-based) → 0-based 1 → user line 1 - 3 = -2 → header error
        String out = "/tmp/x.lua:2: syntax error in header";
        List<LuaProblem> ps = LuaErrorMapper.map(out, HEADER, ONE_TAB);

        assertEquals(1, ps.size());
        assertEquals(0, ps.get(0).getTabIndex());
        assertEquals(0, ps.get(0).getLineNumber());
        assertTrue(ps.get(0).getMessage().contains("Internal error in generated header"));
    }

    // ── Empty / unstructured output ───────────────────────────────────────

    @Test
    void unstructuredOutput_surfacedAtTab0() {
        String out = "segmentation fault (core dumped)";
        List<LuaProblem> ps = LuaErrorMapper.map(out, HEADER, ONE_TAB);

        assertEquals(1, ps.size());
        assertEquals(0, ps.get(0).getTabIndex());
        assertEquals("segmentation fault (core dumped)", ps.get(0).getMessage());
    }

    @Test
    void emptyOutput_returnsEmpty() {
        List<LuaProblem> ps = LuaErrorMapper.map("", HEADER, ONE_TAB);
        assertTrue(ps.isEmpty());
    }
}
