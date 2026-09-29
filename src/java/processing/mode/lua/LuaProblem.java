package processing.mode.lua;

/**
 * A single compile / runtime error from Lua, mapped back to the sketch tab.
 */
public class LuaProblem {

    public enum Severity { ERROR, WARNING }

    private final int    tabIndex;   // 0-based sketch tab
    private final int    lineNumber; // 0-based line within that tab
    private final String message;
    private final Severity severity;

    public LuaProblem(int tabIndex, int lineNumber, String message, Severity severity) {
        this.tabIndex   = tabIndex;
        this.lineNumber = lineNumber;
        this.message    = message;
        this.severity   = severity;
    }

    public int      getTabIndex()   { return tabIndex; }
    public int      getLineNumber() { return lineNumber; }
    public String   getMessage()    { return message; }
    public Severity getSeverity()   { return severity; }
    public boolean  isError()       { return severity == Severity.ERROR; }

    @Override
    public String toString() {
        return String.format("[%s] tab=%d line=%d : %s", severity, tabIndex, lineNumber, message);
    }
}
