package processing.mode.lua;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge-case tests for {@link LuaErrorMapper}.
 */
class LuaErrorMapperEdgeCaseTest {

    private static final int HEADER = 3;

    // ── Whitespace / trimming ─────────────────────────────────────────────

    @Test
    void leadingWhitespace_trimmed() {
        // LuaErrorMapper calls raw.trim() before matching
        String out = "   /tmp/x.lua:5: oops   ";
        List<LuaProblem> ps = LuaErrorMapper.map(out, HEADER, new int[]{3});
        assertEquals(1, ps.size());
        assertEquals("oops", ps.get(0).getMessage());
    }

    // ── Bracket-quoted source name ────────────────────────────────────────

    @Test
    void bracketSourceName() {
        // Lua sometimes produces: [string "..."]:5: ...
        String out = "[string \"local x = 1\"]:5: bad";
        List<LuaProblem> ps = LuaErrorMapper.map(out, HEADER, new int[]{3});
        assertEquals(1, ps.size());
        // We just want it not to crash and to extract "bad"
        assertEquals("bad", ps.get(0).getMessage());
    }

    // ── Multiple error lines: only matching ones are collected ────────────

    @Test
    void multipleErrors() {
        String out = "/tmp/x.lua:4: first error\n" +
                     "not an error line\n" +
                     "/tmp/x.lua:6: second error\n";
        List<LuaProblem> ps = LuaErrorMapper.map(out, HEADER, new int[]{3});
        assertEquals(2, ps.size());
        assertEquals("first error",  ps.get(0).getMessage());
        assertEquals("second error", ps.get(1).getMessage());
    }

    // ── Blank output ──────────────────────────────────────────────────────

    @Test
    void blankOutput_returnsEmpty() {
        List<LuaProblem> ps = LuaErrorMapper.map("   \n\t\n  ", HEADER, new int[]{3});
        assertTrue(ps.isEmpty());
    }
}
