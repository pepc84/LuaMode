package processing.mode.lua;

import ch.usi.si.seart.treesitter.Node;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fast structural lint using the tree-sitter parse tree.
 *
 * Checks performed:
 *   1. Syntax errors / missing tokens reported by tree-sitter (ERROR / MISSING nodes).
 *   2. Calling setup() or draw() explicitly in sketch body code — this is almost
 *      always a mistake in Processing; the framework calls them automatically.
 *   3. Java/JS operator mistakes: {@code !=} (use {@code ~=}), {@code &&} (use {@code and}),
 *      {@code ||} (use {@code or}), leading {@code !} (use {@code not}).
 *      These checks run on raw source text, not the parse tree, and are skipped
 *      inside single-line string literals and single-line comments.
 *
 * This runs after every keystroke (debounced by TsLuaTokenMarker) so it must be
 * cheap — no external processes, no file I/O, no allocations beyond the result list.
 */
public class LuaLinter {

    /**
     * Functions the framework calls on your behalf.
     *
     * <p>Calling any of these directly is almost always a mistake:
     * the event/draw loop manages them automatically.  Note that several
     * of these are also BOOLEAN globals in Processing (e.g. {@code mousePressed}),
     * but the lint check only fires on call expressions ({@code mousePressed()}),
     * so reading the boolean is fine.</p>
     */
    private static final Set<String> FRAMEWORK_FNS = Set.of(
        "setup", "draw",
        "mousePressed", "mouseReleased", "mouseClicked",
        "mouseMoved",   "mouseDragged",
        "keyPressed",   "keyReleased",  "keyTyped"
    );

    // ── Java/JS operator patterns ─────────────────────────────────────────────
    //
    // These catch the three most common mistakes made by users coming from Java or
    // JavaScript, where the Lua equivalent is different.  We scan line-by-line and
    // skip the portion of each line that is a string literal or comment so we don't
    // false-positive on "-- use != instead" or "local s = 'a!=b'".
    //
    // Pattern notes:
    //   !=  → in Lua the inequality operator is ~=.  We match != that is NOT
    //          preceded by ~ (which would be a valid ~= split across chars) and
    //          not preceded by = (which would be "!==", a JS triple-equal, caught
    //          by the same check anyway).
    //   &&  → Lua: and
    //   ||  → Lua: or
    //   !   → unary not; match ! that is not followed by = (which is !=, handled
    //          above).  We only flag it when it appears after a token boundary so
    //          we don't accidentally flag != that were already flagged.

    /** {@code !=} not preceded by {@code ~} */
    private static final Pattern P_NEQ = Pattern.compile("(?<!~)!=");
    /** {@code &&} */
    private static final Pattern P_AND = Pattern.compile("&&");
    /** {@code ||} */
    private static final Pattern P_OR  = Pattern.compile("\\|\\|");
    /** {@code !} not followed by {@code =}, and preceded by a token boundary */
    private static final Pattern P_NOT = Pattern.compile("(?<![~!])!(?!=)");

    /**
     * Lint a pre-parsed tree.
     *
     * @param source         full sketch source as a single string
     * @param root           root node of the tree-sitter parse tree
     * @param tabStartLines  0-based line index of each tab's first line within
     *                       the combined source (length == number of tabs)
     */
    public List<LuaProblem> lint(String source, Node root, int[] tabStartLines) {
        List<LuaProblem> problems = new ArrayList<>();
        walkTree(root, source, tabStartLines, problems);
        scanOperatorMistakes(source, tabStartLines, problems);
        scanApiHints(source, tabStartLines, problems);
        return problems;
    }

    // ── Tree walk ─────────────────────────────────────────────────────────────
    //
    // Single recursive pass: checks every node for the two categories above.
    // Lua tree-sitter node types:
    //   ERROR        — tree-sitter error recovery node
    //   MISSING      — tree-sitter synthesised absent token
    //   function_call — a call expression; first child is the callee expression

    private void walkTree(Node node, String source, int[] tabStartLines,
                          List<LuaProblem> out) {

        String type = node.getType();

        // 1. Syntax error nodes
        if ("ERROR".equals(type)) {
            int line = node.getStartPoint().getRow();
            out.add(makeAt(line, "Syntax error", tabStartLines, LuaProblem.Severity.ERROR));
            // Still recurse — error nodes can have valid children worth checking.
        } else if (node.getType() != null && node.getType().startsWith("MISSING")) {
            int line = node.getStartPoint().getRow();
            out.add(makeAt(line, "Missing '" + type + "'", tabStartLines, LuaProblem.Severity.ERROR));
        }

        // 2. Explicit setup()/draw() calls
        if ("function_call".equals(type)) {
            checkFrameworkCall(node, source, tabStartLines, out);
        }

        // Recurse into children
        for (int i = 0; i < node.getChildCount(); i++) {
            walkTree(node.getChild(i), source, tabStartLines, out);
        }
    }

    /**
     * If this function_call node calls a framework-managed function (setup, draw),
     * emit a warning.
     *
     * Lua tree-sitter grammar shape for a simple call:
     *   function_call
     *     identifier  ← the callee
     *     arguments
     *       arg_list
     *
     * We check only the first child; method calls (obj:method()) won't match.
     */
    private void checkFrameworkCall(Node callNode, String source, int[] tabStartLines,
                                    List<LuaProblem> out) {
        if (callNode.getChildCount() == 0) return;

        Node callee = callNode.getChild(0);
        if (!"identifier".equals(callee.getType())) return;

        String name = nodeText(callee, source);
        if (!FRAMEWORK_FNS.contains(name)) return;

        int line = callNode.getStartPoint().getRow();
        out.add(makeAt(line,
            "You are calling " + name + "() directly. " +
            "Processing calls it automatically, you don't need to.",
            tabStartLines, LuaProblem.Severity.WARNING));
    }

    // ── Java/JS operator scan ─────────────────────────────────────────────────

    /**
     * Scan {@code source} line by line for Java/JS operator mistakes and emit
     * WARNING-level problems.  Portions of each line after a {@code --} comment
     * marker or inside a {@code '…'} / {@code "…"} string literal are skipped
     * so we don't false-positive on explanatory comments or string content.
     *
     * <p>This is also exposed as a package-private static for unit tests:
     * {@link #testScanOperatorMistakes(String, int[])}.</p>
     */
    private static void scanOperatorMistakes(String source, int[] tabStartLines,
                                             List<LuaProblem> out) {
        String[] lines = source.split("\n", -1);
        for (int lineIdx = 0; lineIdx < lines.length; lineIdx++) {
            String code = stripCommentAndStrings(lines[lineIdx]);
            emitIfMatch(P_NEQ, code, lineIdx, "Use '~=' for inequality in Lua (not '!=')",  tabStartLines, out);
            emitIfMatch(P_AND, code, lineIdx, "Use 'and' instead of '&&' in Lua",            tabStartLines, out);
            emitIfMatch(P_OR,  code, lineIdx, "Use 'or' instead of '||' in Lua",             tabStartLines, out);
            emitIfMatch(P_NOT, code, lineIdx, "Use 'not' instead of '!' for boolean negation in Lua", tabStartLines, out);
        }
    }

    /**
     * Return the portion of {@code line} with string-literal contents replaced
     * by spaces and any trailing {@code --} comment stripped.
     *
     * <p>Long strings ({@code [[…]]}) are intentionally NOT handled here because
     * they span multiple lines and require more complex state; single-line heuristic
     * is sufficient for the operator checks.</p>
     */
    static String stripCommentAndStrings(String line) {
        StringBuilder sb = new StringBuilder(line.length());
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            // Single-line comment: rest of line is irrelevant
            if (c == '-' && i + 1 < line.length() && line.charAt(i + 1) == '-') break;
            // String literal: replace contents with spaces so patterns don't match inside
            if (c == '"' || c == '\'') {
                sb.append(c);
                i++;
                while (i < line.length()) {
                    char s = line.charAt(i);
                    if (s == c) { sb.append(s); i++; break; }
                    if (s == '\\') { sb.append("  "); i += 2; continue; }
                    sb.append(' '); i++;
                }
                continue;
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static void emitIfMatch(Pattern p, String code, int lineIdx, String msg,
                                    int[] tabStartLines, List<LuaProblem> out) {
        Matcher m = p.matcher(code);
        if (m.find()) {
            out.add(makeAt(lineIdx, msg, tabStartLines, LuaProblem.Severity.WARNING));
        }
    }

    /** Package-private hook for unit tests — no tree-sitter needed. */
    static List<LuaProblem> testScanOperatorMistakes(String source, int[] tabStartLines) {
        List<LuaProblem> out = new ArrayList<>();
        scanOperatorMistakes(source, tabStartLines, out);
        return out;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Extract the source text of a node by byte range.
     * Returns an empty string if the range is out of bounds.
     */
    private static String nodeText(Node node, String source) {
        int start = node.getStartByte();
        int end   = node.getEndByte();
        if (start < 0 || end > source.length() || start >= end) return "";
        return source.substring(start, end);
    }

    /**
     * Build a {@link LuaProblem} at the given combined-source line, mapping it
     * to the correct sketch tab using {@code tabStartLines}.
     */
    private static LuaProblem makeAt(int combinedLine, String msg,
                                     int[] tabStartLines,
                                     LuaProblem.Severity severity) {
        int tabIndex  = 0;
        int lineInTab = combinedLine;
        for (int t = tabStartLines.length - 1; t >= 0; t--) {
            if (combinedLine >= tabStartLines[t]) {
                tabIndex  = t;
                lineInTab = combinedLine - tabStartLines[t];
                break;
            }
        }
        return new LuaProblem(tabIndex, lineInTab, msg, severity);
    }

    // ── Plain-Lua API hints ───────────────────────────────────────────────────

    private static final Pattern P_MATH_RANDOM = Pattern.compile("\\bmath\\.random\\b");
    private static final Pattern P_MATH_PI     = Pattern.compile("\\bmath\\.pi\\b");
    private static final Pattern P_OS_EXIT     = Pattern.compile("\\bos\\.exit\\b");

    private static void scanApiHints(String source, int[] tabStartLines,
                                     List<LuaProblem> out) {
        String[] lines = source.split("\n", -1);
        for (int lineIdx = 0; lineIdx < lines.length; lineIdx++) {
            String code = stripCommentAndStrings(lines[lineIdx]);
            emitIfMatch(P_MATH_RANDOM, code, lineIdx,
                "Use random() instead of math.random(). Processing's random() is already available",
                tabStartLines, out);
            emitIfMatch(P_MATH_PI, code, lineIdx,
                "Use PI instead of math.pi. Processing provides PI, TWO_PI, HALF_PI as globals",
                tabStartLines, out);
            emitIfMatch(P_OS_EXIT, code, lineIdx,
                "Use exit() instead of os.exit(). Processing's exit() closes the sketch cleanly",
                tabStartLines, out);
        }
    }

    /** Package-private hook for unit tests. */
    static List<LuaProblem> testScanApiHints(String source, int[] tabStartLines) {
        List<LuaProblem> out = new java.util.ArrayList<>();
        scanApiHints(source, tabStartLines, out);
        return out;
    }

}
