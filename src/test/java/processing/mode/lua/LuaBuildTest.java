package processing.mode.lua;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for pure-logic helpers in {@link LuaBuild}.
 *
 * Does not require the Processing IDE runtime, luamode-runner, or native libs.
 */
class LuaBuildTest {

    // ── countLines ────────────────────────────────────────────────────────────

    @Test
    void countLines_null() {
        assertEquals(0, LuaBuild.testCountLines(null));
    }

    @Test
    void countLines_empty() {
        assertEquals(0, LuaBuild.testCountLines(""));
    }

    @Test
    void countLines_noNewline() {
        assertEquals(0, LuaBuild.testCountLines("hello"));
    }

    @Test
    void countLines_oneNewline() {
        assertEquals(1, LuaBuild.testCountLines("hello\n"));
    }

    @Test
    void countLines_multipleNewlines() {
        assertEquals(3, LuaBuild.testCountLines("a\nb\nc\n"));
    }

    @Test
    void countLines_midlineNewlines() {
        // "a\nb\nc" has 2 newlines
        assertEquals(2, LuaBuild.testCountLines("a\nb\nc"));
    }

    @Test
    void countLines_windowsLineEndings() {
        // \r\n — only \n is counted
        assertEquals(2, LuaBuild.testCountLines("a\r\nb\r\nc"));
    }

    @Test
    void countLines_onlyNewlines() {
        assertEquals(5, LuaBuild.testCountLines("\n\n\n\n\n"));
    }

    @Test
    void countLines_longText() {
        // 100 lines separated by \n
        String s = "x\n".repeat(100);
        assertEquals(100, LuaBuild.testCountLines(s));
    }

    // ── Running-counter arithmetic (mirrors buildSource logic) ────────────────
    //
    // Verifies that the per-tab line contribution used in buildSource is
    // consistent with countLines: a tab whose source ends with \n contributes
    // exactly countLines(prog) newlines and no extra; one without a trailing \n
    // contributes countLines(prog) + 1 (the \n we append ourselves).

    @Test
    void runningCounter_tabWithTrailingNewline() {
        String prog = "x = 1\ny = 2\n";  // 2 newlines, trailing \n
        int contribution = LuaBuild.testCountLines(prog) + (prog.endsWith("\n") ? 0 : 1);
        assertEquals(2, contribution,
            "Tab with trailing \\n should contribute countLines lines");
    }

    @Test
    void runningCounter_tabWithoutTrailingNewline() {
        String prog = "x = 1\ny = 2";    // 1 newline, no trailing \n
        int contribution = LuaBuild.testCountLines(prog) + (prog.endsWith("\n") ? 0 : 1);
        assertEquals(2, contribution,
            "Tab without trailing \\n should contribute countLines+1 lines");
    }

    @Test
    void runningCounter_singleLineNoNewline() {
        String prog = "ellipse(0,0,10,10)";
        int contribution = LuaBuild.testCountLines(prog) + (prog.endsWith("\n") ? 0 : 1);
        assertEquals(1, contribution);
    }

    @Test
    void runningCounter_singleLineWithNewline() {
        String prog = "ellipse(0,0,10,10)\n";
        int contribution = LuaBuild.testCountLines(prog) + (prog.endsWith("\n") ? 0 : 1);
        assertEquals(1, contribution);
    }

    @Test
    void runningCounter_emptyProg() {
        // Empty program is skipped by appendTabs, but countLines should still be safe
        assertEquals(0, LuaBuild.testCountLines(""));
    }

    // ── Simulated multi-tab tabStartLines computation ─────────────────────────
    //
    // Replays the buildSource loop without Sketch to verify the running counter
    // produces the same results as the old O(n²) approach for a known layout.

    @Test
    void simulatedTabStartLines_multipleTabsMatch() {
        int headerLines = 3;
        String[] progs = {
            "-- tab 0 line 0\n-- tab 0 line 1\n",       // 2 lines, trailing \n
            "-- tab 1 line 0\n",                          // 1 line,  trailing \n
            "-- tab 2 line 0\n-- tab 2 line 1\n-- tab 2 line 2\n",  // 3 lines
        };

        int[] tabStartLines = new int[progs.length];
        int   running       = headerLines;
        for (int i = 0; i < progs.length; i++) {
            tabStartLines[i] = running;
            String prog = progs[i];
            running += LuaBuild.testCountLines(prog) + (prog.endsWith("\n") ? 0 : 1);
        }

        // Expected:
        //   tab 0 starts at header (3)
        //   tab 1 starts at 3 + 2 = 5
        //   tab 2 starts at 5 + 1 = 6
        assertArrayEquals(new int[]{3, 5, 6}, tabStartLines);
    }
}
