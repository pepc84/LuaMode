package processing.mode.lua;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LuaProblem}.
 */
class LuaProblemTest {

    @Test
    void fields_roundTrip() {
        LuaProblem p = new LuaProblem(2, 7, "undefined variable 'x'", LuaProblem.Severity.ERROR);
        assertEquals(2, p.getTabIndex());
        assertEquals(7, p.getLineNumber());
        assertEquals("undefined variable 'x'", p.getMessage());
        assertEquals(LuaProblem.Severity.ERROR, p.getSeverity());
    }

    @Test
    void isError_errorSeverity() {
        LuaProblem p = new LuaProblem(0, 0, "msg", LuaProblem.Severity.ERROR);
        assertTrue(p.isError());
    }

    @Test
    void isError_warningSeverity() {
        LuaProblem p = new LuaProblem(0, 0, "msg", LuaProblem.Severity.WARNING);
        assertFalse(p.isError());
    }

    @Test
    void toString_containsAllFields() {
        LuaProblem p = new LuaProblem(1, 3, "oops", LuaProblem.Severity.WARNING);
        String s = p.toString();
        assertTrue(s.contains("WARNING"), "toString should include severity");
        assertTrue(s.contains("1"),       "toString should include tab index");
        assertTrue(s.contains("3"),       "toString should include line number");
        assertTrue(s.contains("oops"),    "toString should include message");
    }

    @Test
    void zeroTabAndLine() {
        LuaProblem p = new LuaProblem(0, 0, "", LuaProblem.Severity.ERROR);
        assertEquals(0, p.getTabIndex());
        assertEquals(0, p.getLineNumber());
    }
}
