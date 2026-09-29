package processing.mode.lua;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for pure-logic helpers in {@link TsLuaTokenMarker}.
 *
 * Does not require the native tree-sitter library.
 */
class TsLuaTokenMarkerTest {

    // ── buildLineOffsets ──────────────────────────────────────────────────────

    @Test
    void emptySource_singleOffset() {
        int[] offsets = TsLuaTokenMarker.testBuildLineOffsets("");
        assertArrayEquals(new int[]{0}, offsets, "empty source should yield [{0}]");
    }

    @Test
    void singleLine_noNewline() {
        // "hello" — one line, starts at 0
        int[] offsets = TsLuaTokenMarker.testBuildLineOffsets("hello");
        assertArrayEquals(new int[]{0}, offsets);
    }

    @Test
    void singleLine_withTrailingNewline() {
        // "hello\n" — two entries: line 0 at 0, line 1 at 6 (empty line after \n)
        int[] offsets = TsLuaTokenMarker.testBuildLineOffsets("hello\n");
        assertEquals(2, offsets.length);
        assertEquals(0, offsets[0]);
        assertEquals(6, offsets[1]);
    }

    @Test
    void twoLines() {
        // "ab\ncd" — line 0 at 0, line 1 at 3
        int[] offsets = TsLuaTokenMarker.testBuildLineOffsets("ab\ncd");
        assertArrayEquals(new int[]{0, 3}, offsets);
    }

    @Test
    void threeLines() {
        // "a\nbb\nccc"
        int[] offsets = TsLuaTokenMarker.testBuildLineOffsets("a\nbb\nccc");
        assertArrayEquals(new int[]{0, 2, 5}, offsets);
    }

    @Test
    void multipleTrailingNewlines() {
        // "x\n\n\n" → 4 lines (3 newlines = 3 splits → 4 entries)
        int[] offsets = TsLuaTokenMarker.testBuildLineOffsets("x\n\n\n");
        assertEquals(4, offsets.length);
        assertEquals(0, offsets[0]);
        assertEquals(2, offsets[1]);  // after first \n
        assertEquals(3, offsets[2]);  // after second \n
        assertEquals(4, offsets[3]);  // after third \n
    }

    @Test
    void lineCountMatchesNewlineCount() {
        String src = "line1\nline2\nline3\nline4";
        int[] offsets = TsLuaTokenMarker.testBuildLineOffsets(src);
        // 3 newlines → 4 lines
        assertEquals(4, offsets.length);
    }

    @Test
    void offsetsPointToCorrectCharacters() {
        String src = "abc\ndefg\nhi";
        int[] offsets = TsLuaTokenMarker.testBuildLineOffsets(src);
        assertEquals(3, offsets.length);
        assertEquals('a', src.charAt(offsets[0]));
        assertEquals('d', src.charAt(offsets[1]));
        assertEquals('h', src.charAt(offsets[2]));
    }

    @Test
    void singleCharLines() {
        // "a\nb\nc"
        String src = "a\nb\nc";
        int[] offsets = TsLuaTokenMarker.testBuildLineOffsets(src);
        assertArrayEquals(new int[]{0, 2, 4}, offsets);
    }

    @Test
    void consecutiveNewlines_createEmptyLines() {
        // "\n\n" → 3 lines: empty, empty, empty
        String src = "\n\n";
        int[] offsets = TsLuaTokenMarker.testBuildLineOffsets(src);
        assertArrayEquals(new int[]{0, 1, 2}, offsets);
    }

    @Test
    void unicodeSingleCodeUnit_countedByCharIndex() {
        // BMP chars: each is one char. "αβ\nγ"
        String src = "αβ\nγ";
        int[] offsets = TsLuaTokenMarker.testBuildLineOffsets(src);
        assertEquals(2, offsets.length);
        assertEquals(0, offsets[0]);
        assertEquals(3, offsets[1]);  // α(0), β(1), \n(2), γ(3)
    }

    @Test
    void firstOffsetAlwaysZero() {
        for (String src : new String[]{"", "x", "x\ny", "\n"}) {
            int[] offsets = TsLuaTokenMarker.testBuildLineOffsets(src);
            assertEquals(0, offsets[0], "First offset must always be 0 for: " + repr(src));
        }
    }

    @Test
    void lineCount_equalsNewlineCountPlusOne() {
        String[] cases = {"", "no newlines", "one\n", "two\nnewlines\n", "a\nb\nc\nd"};
        for (String src : cases) {
            int newlines = (int) src.chars().filter(c -> c == '\n').count();
            int[] offsets = TsLuaTokenMarker.testBuildLineOffsets(src);
            assertEquals(newlines + 1, offsets.length,
                "Line count wrong for: " + repr(src));
        }
    }

    @Test
    void offsetsAreStrictlyIncreasing() {
        String src = "line1\nline2\nline3\n";
        int[] offsets = TsLuaTokenMarker.testBuildLineOffsets(src);
        for (int i = 1; i < offsets.length; i++) {
            assertTrue(offsets[i] > offsets[i - 1],
                "Offsets must be strictly increasing at index " + i);
        }
    }

    @Test
    void allOffsetsWithinSourceBounds() {
        String src = "a\nbb\nccc\n";
        int[] offsets = TsLuaTokenMarker.testBuildLineOffsets(src);
        for (int i = 0; i < offsets.length; i++) {
            assertTrue(offsets[i] >= 0 && offsets[i] <= src.length(),
                "Offset " + offsets[i] + " at index " + i + " is out of bounds");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String repr(String s) {
        return "\"" + s.replace("\n", "\\n") + "\"";
    }
}
