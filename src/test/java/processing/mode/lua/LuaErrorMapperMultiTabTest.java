package processing.mode.lua;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Multi-tab and edge-case tests for {@link LuaErrorMapper}.
 *
 * Invariants under test:
 *   - A 5-tab sketch maps errors to the correct tab and line-within-tab.
 *   - An error on the exact first line of a tab maps to line 0 of that tab.
 *   - An error on the exact last line of a tab maps to that tab, not the next.
 *   - A colon inside the message body does not confuse line extraction.
 *   - An empty tabStartLines array does not throw.
 *   - Trailing carriage-returns (Windows line endings) are tolerated.
 */
class LuaErrorMapperMultiTabTest {

    // Layout:
    //   header = 3 lines (lines 0-2 combined, 1-based 1-3)
    //   tab 0: starts at combined line 3, is 4 lines  → combined 3-6
    //   tab 1: starts at combined line 7, is 3 lines  → combined 7-9
    //   tab 2: starts at combined line 10, is 5 lines → combined 10-14
    //   tab 3: starts at combined line 15, is 2 lines → combined 15-16
    //   tab 4: starts at combined line 17             → combined 17+
    private static final int   HEADER   = 3;
    private static final int[] FIVE_TABS = {3, 7, 10, 15, 17};

    @Test
    void fiveTabs_errorInTab0() {
        // Combined line 4 (1-based) → 0-based 3 → user line 0 → tab 0, line 0
        List<LuaProblem> ps = LuaErrorMapper.map("/tmp/x.lua:4: bad", HEADER, FIVE_TABS);
        assertEquals(1, ps.size());
        assertEquals(0, ps.get(0).getTabIndex());
        assertEquals(0, ps.get(0).getLineNumber());
    }

    @Test
    void fiveTabs_errorInTab2() {
        // Combined line 12 (1-based) → 0-based 11 → user line 8 → tab 2 (user offset 7), line 1
        List<LuaProblem> ps = LuaErrorMapper.map("/tmp/x.lua:12: nil", HEADER, FIVE_TABS);
        assertEquals(1, ps.size());
        assertEquals(2, ps.get(0).getTabIndex());
        assertEquals(1, ps.get(0).getLineNumber());
    }

    @Test
    void fiveTabs_errorInTab4() {
        // Combined line 19 (1-based) → 0-based 18 → user line 15 → tab 4 (user offset 14), line 1
        List<LuaProblem> ps = LuaErrorMapper.map("/tmp/x.lua:19: err", HEADER, FIVE_TABS);
        assertEquals(1, ps.size());
        assertEquals(4, ps.get(0).getTabIndex());
        assertEquals(1, ps.get(0).getLineNumber());
    }

    @Test
    void firstLineOfTab_mapsToLine0() {
        // Tab 3 starts at combined line 15 (1-based 16)
        // Combined line 16 (1-based) → 0-based 15 → user line 12 → tab 3 (user offset 12), line 0
        List<LuaProblem> ps = LuaErrorMapper.map("/tmp/x.lua:16: err", HEADER, FIVE_TABS);
        assertEquals(3, ps.get(0).getTabIndex());
        assertEquals(0, ps.get(0).getLineNumber());
    }

    @Test
    void lastLineOfTab1_notMappedToTab2() {
        // Tab 1 covers combined 0-based lines 7-9; last line is 9.
        // Combined line 10 (1-based) → 0-based 9 → user line 6 → tab 1 (user offset 4), line 2
        // Tab 2 starts at combined 10 (user offset 7); 6 < 7, so stays in tab 1.
        List<LuaProblem> ps = LuaErrorMapper.map("/tmp/x.lua:10: msg", HEADER, FIVE_TABS);
        assertEquals(1, ps.get(0).getTabIndex());
        assertEquals(2, ps.get(0).getLineNumber());
    }

    @Test
    void exactFirstLineOfTab2_mapsToTab2Line0() {
        // Tab 2 starts at combined 0-based line 10.
        // Combined line 11 (1-based) → 0-based 10 → user line 7 → tab 2 (user offset 7), line 0
        List<LuaProblem> ps = LuaErrorMapper.map("/tmp/x.lua:11: msg", HEADER, FIVE_TABS);
        assertEquals(2, ps.get(0).getTabIndex());
        assertEquals(0, ps.get(0).getLineNumber());
    }

    // ── Message with colons ───────────────────────────────────────────────────

    @Test
    void messageContainingColons_extractedCorrectly() {
        // "attempt to call a nil value (global 'foo:bar')" — colon in message
        String out = "/tmp/x.lua:5: attempt to call a nil value (global 'foo:bar')";
        List<LuaProblem> ps = LuaErrorMapper.map(out, HEADER, new int[]{3});
        assertEquals(1, ps.size());
        assertTrue(ps.get(0).getMessage().contains("foo:bar"),
            "Colon inside message should be preserved");
    }

    @Test
    void messageWithTimestamp_extractedCorrectly() {
        // Pathological: message itself looks like an error line
        String out = "/tmp/x.lua:5: error at 12:30:00";
        List<LuaProblem> ps = LuaErrorMapper.map(out, HEADER, new int[]{3});
        assertEquals(1, ps.size());
        assertEquals("error at 12:30:00", ps.get(0).getMessage());
    }

    // ── Windows line endings ──────────────────────────────────────────────────

    @Test
    void windowsLineEndings_tolerated() {
        String out = "/tmp/x.lua:5: bad\r\nnot an error\r\n/tmp/x.lua:7: worse\r\n";
        List<LuaProblem> ps = LuaErrorMapper.map(out, HEADER, new int[]{3});
        // trim() strips \r, so both lines match
        assertEquals(2, ps.size());
        assertEquals("bad",   ps.get(0).getMessage());
        assertEquals("worse", ps.get(1).getMessage());
    }

    // ── Empty / degenerate tabStartLines ─────────────────────────────────────

    @Test
    void emptyTabStartLines_doesNotThrow() {
        // No tabs at all — degenerate but should not crash
        assertDoesNotThrow(() ->
            LuaErrorMapper.map("/tmp/x.lua:5: err", HEADER, new int[]{}));
    }

    @Test
    void singleTab_atLine0() {
        // Tab 0 starts at combined line 0 (no header)
        List<LuaProblem> ps = LuaErrorMapper.map("/tmp/x.lua:3: err", 0, new int[]{0});
        assertEquals(0, ps.get(0).getTabIndex());
        assertEquals(2, ps.get(0).getLineNumber());  // 1-based 3 → 0-based 2
    }

    // ── Severity is always ERROR from the mapper ──────────────────────────────

    @Test
    void mappedProblems_alwaysError() {
        List<LuaProblem> ps = LuaErrorMapper.map("/tmp/x.lua:5: oops", HEADER, new int[]{3});
        assertEquals(LuaProblem.Severity.ERROR, ps.get(0).getSeverity());
        assertTrue(ps.get(0).isError());
    }

    // ── Null-safety: empty output string ─────────────────────────────────────

    @Test
    void unstructuredMultilineOutput_surfacedAsOneRawProblem() {
        // When no lines match the Lua error pattern, the whole output is returned as one raw problem
        String out = "not a lua error line at all\nanother non-matching line";
        List<LuaProblem> ps = LuaErrorMapper.map(out, HEADER, new int[]{3});
        assertEquals(1, ps.size());
        assertTrue(ps.get(0).getMessage().contains("not a lua error"), "raw message preserved");
    }
}
