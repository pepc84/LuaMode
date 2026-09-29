package processing.mode.lua;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the pure-logic helpers in {@link LuaInputHandler}.
 *
 * {@code shouldOverType} and {@code shouldSuppressSingleQuote} contain all the
 * decision logic; the side-effecting methods (setCaretPosition, setSelectedText)
 * depend on {@code JEditTextArea} and are tested indirectly by integration.
 */
class LuaInputHandlerTest {

    // ── shouldOverType ────────────────────────────────────────────────────────

    @Test
    void overType_matchingCharAtCaret() {
        // Cursor at 3, next char is ')' → should over-type
        assertTrue(LuaInputHandler.shouldOverType("foo)", 3, ')'));
    }

    @Test
    void overType_differentCharAtCaret() {
        // Next char is ']', typed ')' → no over-type
        assertFalse(LuaInputHandler.shouldOverType("foo]", 3, ')'));
    }

    @Test
    void overType_caretAtEnd_neverOverTypes() {
        // pos == text.length() → out of bounds → false
        assertFalse(LuaInputHandler.shouldOverType("foo", 3, ')'));
    }

    @Test
    void overType_caretBeyondEnd_neverOverTypes() {
        assertFalse(LuaInputHandler.shouldOverType("", 5, ')'));
    }

    @Test
    void overType_emptyText() {
        assertFalse(LuaInputHandler.shouldOverType("", 0, ')'));
    }

    @Test
    void overType_matchingDoubleQuote() {
        // "foo"|" → should skip the closing "
        assertTrue(LuaInputHandler.shouldOverType("foo\"", 3, '"'));
    }

    @Test
    void overType_matchingCloseBrace() {
        assertTrue(LuaInputHandler.shouldOverType("x}", 1, '}'));
    }

    @Test
    void overType_closingBracket_atStart() {
        // cursor at 0, next char is ']'
        assertTrue(LuaInputHandler.shouldOverType("]", 0, ']'));
    }

    @Test
    void overType_wrongQuoteType() {
        // next char is ' but typed "
        assertFalse(LuaInputHandler.shouldOverType("foo'", 3, '"'));
    }

    // ── shouldSuppressSingleQuote ─────────────────────────────────────────────

    @Test
    void suppress_afterLetter_yes() {
        // "it|'" — pos=2, text.charAt(1)='t' (letter) → suppress pair
        assertTrue(LuaInputHandler.shouldSuppressSingleQuote("it", 2));
    }

    @Test
    void suppress_afterDigit_yes() {
        // "3|'" — pos=1, text.charAt(0)='3' (digit) → suppress pair
        assertTrue(LuaInputHandler.shouldSuppressSingleQuote("3", 1));
    }

    @Test
    void suppress_afterSpace_no() {
        // " |'" — pos=1, text.charAt(0)=' ' → insert pair
        assertFalse(LuaInputHandler.shouldSuppressSingleQuote(" ", 1));
    }

    @Test
    void suppress_afterOpenParen_no() {
        assertFalse(LuaInputHandler.shouldSuppressSingleQuote("(", 1));
    }

    @Test
    void suppress_atStart_no() {
        // pos=0 → no predecessor → insert pair
        assertFalse(LuaInputHandler.shouldSuppressSingleQuote("", 0));
        assertFalse(LuaInputHandler.shouldSuppressSingleQuote("x", 0));
    }

    @Test
    void suppress_afterUnderscore_no() {
        // Underscore is not a letter/digit per Character.isLetterOrDigit
        assertFalse(LuaInputHandler.shouldSuppressSingleQuote("_", 1));
    }

    @Test
    void suppress_afterUpperCaseLetter_yes() {
        assertTrue(LuaInputHandler.shouldSuppressSingleQuote("A", 1));
    }

    @Test
    void suppress_afterNewline_no() {
        assertFalse(LuaInputHandler.shouldSuppressSingleQuote("\n", 1));
    }

    // ── Combined scenario: "it's" contraction ─────────────────────────────────

    @Test
    void contraction_apostropheAfterT() {
        // text = "it", caret at 2 → suppress (insert plain ')
        assertTrue(LuaInputHandler.shouldSuppressSingleQuote("it", 2));
        // text after insertion: "it'" — that's the desired contraction behaviour
    }

    // ── Combined scenario: blank line, pair should be inserted ────────────────

    @Test
    void emptyLine_pairInserted() {
        // text = "", caret at 0 → no over-type, no suppression → pair
        assertFalse(LuaInputHandler.shouldOverType("", 0, '\''));
        assertFalse(LuaInputHandler.shouldSuppressSingleQuote("", 0));
    }

    // ── shouldOverType and shouldSuppressSingleQuote are independent ──────────

    @Test
    void independence_overTypeCheckIgnoresPredecessor() {
        // shouldOverType only looks at text[pos], not text[pos-1]
        // "a'" at pos=1 → next char is ' → should over-type regardless of 'a' before it
        assertTrue(LuaInputHandler.shouldOverType("a'", 1, '\''));
    }
}
