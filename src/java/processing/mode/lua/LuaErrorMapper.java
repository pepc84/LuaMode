package processing.mode.lua;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps Lua interpreter error output back to sketch tabs.
 *
 * Lua errors look like:
 *   input:12: attempt to index a nil value (global 'foo')
 *   [string "..."]:5: ...
 *   /tmp/processing_lua_12345.lua:8: ...
 *
 * This class is pure static — no state, no I/O.
 */
public final class LuaErrorMapper {

    // Matches:  <path>:<line>: <message>
    //
    // The path is matched lazily so the FIRST :<digits>: wins; a message like
    // "error at 12:30:00" must not be read as line 30. Windows paths still work:
    // in C:\tmp\foo.lua:12: the drive colon isn't followed by digits, so the
    // lazy match moves past it to :12:.
    private static final Pattern LUA_ERROR =
        Pattern.compile("^(.+?):(\\d+):\\s*(.+)$");

    private LuaErrorMapper() {}

    /**
     * Parse interpreter output into a list of {@link LuaProblem}s,
     * mapping combined-source line numbers back to sketch tabs.
     *
     * @param output        raw stderr / stdout from the Lua interpreter
     * @param headerLines   number of generated header lines prepended before user code
     * @param tabStartLines first combined-source line of each tab (0-based, length = tab count)
     */
    public static List<LuaProblem> map(String output, int headerLines, int[] tabStartLines) {
        List<LuaProblem> problems = new ArrayList<>();

        for (String raw : output.lines().toList()) {
            // Everything after "stack traceback:" repeats locations of the error
            // already reported; reporting them again would duplicate it.
            if (raw.trim().equals("stack traceback:")) break;
            Matcher m = LUA_ERROR.matcher(raw.trim());
            if (!m.matches()) continue;

            int    combinedLine = Integer.parseInt(m.group(2)) - 1; // convert to 0-based
            String message      = m.group(3).trim();

            int userLine = combinedLine - headerLines;
            if (userLine < 0) {
                // Error in the generated header — surface at tab 0, line 0
                problems.add(new LuaProblem(0, 0,
                    "Internal error in generated header: " + message,
                    LuaProblem.Severity.ERROR));
                continue;
            }

            problems.add(new LuaProblem(
                tabIndex(userLine, tabStartLines, headerLines),
                lineInTab(userLine, tabStartLines, headerLines),
                message,
                LuaProblem.Severity.ERROR
            ));
        }

        if (problems.isEmpty() && !output.isBlank()) {
            // Unstructured error — no line info, surface at top of sketch
            problems.add(new LuaProblem(0, 0, output.trim(), LuaProblem.Severity.ERROR));
        }

        return problems;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static int tabIndex(int userLine, int[] tabStartLines, int headerLines) {
        for (int t = tabStartLines.length - 1; t >= 0; t--) {
            if (userLine >= tabStartLines[t] - headerLines) return t;
        }
        return 0;
    }

    private static int lineInTab(int userLine, int[] tabStartLines, int headerLines) {
        if (tabStartLines.length == 0) return userLine;   // no tab info: whole sketch is one tab
        int tab = tabIndex(userLine, tabStartLines, headerLines);
        return userLine - (tabStartLines[tab] - headerLines);
    }
}
