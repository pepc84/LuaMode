package processing.mode.lua;

import ch.usi.si.seart.treesitter.Language;
import ch.usi.si.seart.treesitter.Node;
import ch.usi.si.seart.treesitter.Query;
import ch.usi.si.seart.treesitter.QueryCapture;
import ch.usi.si.seart.treesitter.QueryCursor;
import ch.usi.si.seart.treesitter.QueryMatch;

import processing.app.syntax.SyntaxDocument;
import processing.app.syntax.Token;
import processing.app.syntax.TokenMarker;

import javax.swing.text.Segment;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Async tree-sitter token marker for Lua.
 *
 * Architecture mirrors CppMode's TsTokenMarker:
 *   • A background scheduler debounces source changes (150 ms).
 *   • {@link #rebuildHighlights()} parses via {@link TsLuaEngine} and runs
 *     the highlights query, building a {@code byte[]} parallel to the source.
 *   • {@link #markTokensImpl} walks that byte[] on the EDT per rendered line.
 */
public class TsLuaTokenMarker extends TokenMarker {

    // ── State ─────────────────────────────────────────────────────────────
    private static final byte[] EMPTY        = new byte[0];
    private static final int[]  EMPTY_LINES  = new int[0];

    /** Parallel to the source: one token-type byte per source character. */
    private final AtomicReference<byte[]> highlights  = new AtomicReference<>(EMPTY);
    /**
     * Line-start byte offsets, precomputed alongside {@link #highlights}.
     * {@code lineOffsets[i]} is the index of the first byte of line {@code i}
     * in the source (0-based). Replaced atomically with {@link #highlights}
     * (both fields are written under {@code this}, read without locks in
     * {@link #markTokensImpl} — the worst case is a stale read which just
     * shows the previous highlight, not a crash).
     */
    private volatile int[]      lineOffsets  = EMPTY_LINES;
    private volatile String     highlightedSource = "";
    private volatile SyntaxDocument  syntaxDoc;

    // ── Linter ────────────────────────────────────────────────────────────
    private final LuaLinter linter = new LuaLinter();
    private volatile Consumer<List<LuaProblem>> lintConsumer;
    private volatile Runnable repaintHook;

    // The token marker always receives a single tab's source, so tabStartLines
    // is always {0} here.  LuaErrorMapper handles multi-tab mapping separately.
    private static final int[] SINGLE_TAB = {0};

    // ── Background scheduler ──────────────────────────────────────────────
    private final ScheduledExecutorService scheduler;
    private volatile ScheduledFuture<?> pending;
    private static final long DEBOUNCE_MS = 150;

    // ── Cached query ──────────────────────────────────────────────────────
    private volatile Query tsQuery;

    // java-tree-sitter 1.9.1 does not evaluate query predicates, so a pattern
    // like ((identifier) @function.builtin (#match? @function.builtin "^(fill|rect)$"))
    // matches EVERY identifier. We read the #match? regexes out of the .scm
    // ourselves and check captured text against them. Capture name -> regexes.
    private volatile Map<String, List<Pattern>> matchPredicates = Map.of();
    private static final Pattern MATCH_PREDICATE =
        Pattern.compile("\\(#match\\?\\s+@([\\w.]+)\\s+\"((?:[^\"\\\\]|\\\\.)*)\"\\s*\\)");

    public TsLuaTokenMarker() {
        ScheduledThreadPoolExecutor ex =
            new ScheduledThreadPoolExecutor(1, r -> {
                Thread t = new Thread(r, "lua-ts-highlighter");
                t.setDaemon(true);
                return t;
            });
        ex.setRemoveOnCancelPolicy(true);
        scheduler = ex;
    }

    // ── TokenMarker overrides ─────────────────────────────────────────────

    @Override
    public void addColoring(String keyword, String type) {
        // no-op: tree-sitter drives all coloring
    }

    @Override
    public byte markTokensImpl(byte token, Segment line, int lineIndex) {
        byte[] hl      = highlights.get();
        int[]  offsets = lineOffsets;   // read once; consistent snapshot with hl

        if (hl.length == 0 || offsets.length == 0) {
            addToken(line.count, Token.NULL);
            return Token.NULL;
        }

        // O(1) line-start lookup using the precomputed offset table.
        int lineStart = (lineIndex < offsets.length) ? offsets[lineIndex] : hl.length;

        // Walk the highlight array for each character on this line.
        int  pos     = lineStart;
        int  start   = 0;
        byte lastTok = Token.NULL;

        for (int col = 0; col < line.count; col++, pos++) {
            byte tok = (pos < hl.length) ? hl[pos] : Token.NULL;
            if (tok != lastTok) {
                if (col > start) addToken(col - start, lastTok);
                start   = col;
                lastTok = tok;
            }
        }
        addToken(line.count - start, lastTok);
        return Token.NULL;
    }

    // ── Linter wiring ─────────────────────────────────────────────────────

    /**
     * Register a consumer that will be called (on a background thread) after
     * each successful parse.  Pass {@code null} to unregister.
     */
    public void setLintConsumer(Consumer<List<LuaProblem>> consumer) {
        this.lintConsumer = consumer;
    }

    /**
     * Called (on the highlighter thread) after new highlights are ready. The
     * document doesn't change when colors do, so without this the text area
     * wouldn't repaint until something else made it.
     */
    public void setRepaintHook(Runnable hook) {
        this.repaintHook = hook;
    }

    // ── Invalidation / rebuild ────────────────────────────────────────────

    public synchronized void invalidate(SyntaxDocument doc) {
        this.syntaxDoc = doc;
        if (pending != null) pending.cancel(false);
        pending = scheduler.schedule(this::rebuildHighlights, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    private void rebuildHighlights() {
        SyntaxDocument doc = syntaxDoc;
        if (doc == null) return;

        String src;
        try {
            src = doc.getText(0, doc.getLength());
        } catch (Exception e) {
            return;
        }

        Query q = ensureQuery();
        if (q == null) return;

        try (TsLuaEngine.ParseResult pr = TsLuaEngine.get().parse(src)) {
            if (pr == null) return;

            byte[] hl   = new byte[src.length()];
            byte[] rank = new byte[src.length()];   // priority of what's painted at each char
            Map<String, List<Pattern>> preds = matchPredicates;

            try (QueryCursor cursor = pr.root.walk(q)) {
                for (QueryMatch match : cursor) {
                    for (java.util.Map.Entry<ch.usi.si.seart.treesitter.Capture,
                            java.util.Collection<Node>> entry
                            : match.getCaptures().entrySet()) {
                        String captureName = entry.getKey().getName();
                        byte   tokType     = highlightNameToToken(captureName);
                        if (tokType == Token.NULL) continue;
                        byte   r           = captureRank(captureName);
                        List<Pattern> regexes = preds.get(captureName);
                        for (Node node : entry.getValue()) {
                            // offsets are Java char indexes (the parser reads UTF-16)
                            int start = Math.min(node.getStartByte(), src.length());
                            int end   = Math.min(node.getEndByte(),   src.length());
                            if (regexes != null && !matchesAny(regexes, src.substring(start, end))) continue;
                            for (int b = start; b < end; b++) {
                                if (r >= rank[b]) { hl[b] = tokType; rank[b] = r; }
                            }
                        }
                    }
                }
            }

            // Precompute line-start offsets so markTokensImpl is O(1) per line.
            int[] offsets = buildLineOffsets(src);

            highlightedSource = src;
            lineOffsets = offsets;   // write lineOffsets before highlights so
            highlights.set(hl);      // markTokensImpl never sees a stale pair

            // Run the linter on the same parse result
            Consumer<List<LuaProblem>> consumer = lintConsumer;
            if (consumer != null) {
                List<LuaProblem> problems = linter.lint(src, pr.root, SINGLE_TAB);
                consumer.accept(problems);
            }

            Runnable hook = repaintHook;
            if (hook != null) hook.run();
        }
    }

    // ── Line offset table ─────────────────────────────────────────────────

    /** Package-private hook for tests. */
    static int[] testBuildLineOffsets(String src) { return buildLineOffsets(src); }

    /**
     * Returns an array where {@code result[i]} is the index of the first
     * character of line {@code i} in {@code src}.  Line 0 always starts at 0.
     * Built once per rebuild; makes {@link #markTokensImpl} O(1) per line
     * instead of O(lines-before-current-line).
     */
    private static int[] buildLineOffsets(String src) {
        if (src.isEmpty()) return new int[]{0};
        // Count newlines first so we can allocate the exact array.
        int lineCount = 1;
        for (int i = 0; i < src.length(); i++) {
            if (src.charAt(i) == '\n') lineCount++;
        }
        int[] offsets = new int[lineCount];
        int   line    = 0;
        offsets[0]    = 0;
        for (int i = 0; i < src.length(); i++) {
            if (src.charAt(i) == '\n' && line + 1 < lineCount) {
                offsets[++line] = i + 1;
            }
        }
        return offsets;
    }

    // ── Query loading ─────────────────────────────────────────────────────

    private Query ensureQuery() {
        if (tsQuery != null) return tsQuery;
        synchronized (this) {
            if (tsQuery != null) return tsQuery;
            try {
                String scm = loadResource("/queries/highlights.scm");
                matchPredicates = parseMatchPredicates(scm);
                tsQuery = Query.getFor(Language.LUA, scm);
            } catch (Exception e) {
                System.err.println("[LuaMode] Failed to load highlights.scm: " + e.getMessage());
            }
        }
        return tsQuery;
    }

    private static String loadResource(String path) throws IOException {
        try (InputStream in = TsLuaTokenMarker.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("Resource not found: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ── Predicates and priority ───────────────────────────────────────────

    /** Package-private for tests. All #match? regexes in the query, by capture name. */
    static Map<String, List<Pattern>> parseMatchPredicates(String scm) {
        Map<String, List<Pattern>> out = new HashMap<>();
        Matcher m = MATCH_PREDICATE.matcher(scm);
        while (m.find()) {
            String regex = m.group(2).replace("\\\\", "\\");   // unescape the .scm string
            out.computeIfAbsent(m.group(1), k -> new ArrayList<>()).add(Pattern.compile(regex));
        }
        return out;
    }

    private static boolean matchesAny(List<Pattern> regexes, String text) {
        for (Pattern p : regexes) if (p.matcher(text).find()) return true;
        return false;
    }

    /**
     * Where captures overlap, the higher rank wins: a comment or string is
     * never recolored by something inside it, and a Processing builtin like
     * size() stays a builtin even though the call pattern also matches it.
     */
    private static byte captureRank(String name) {
        return switch (name) {
            case "comment", "string"                   -> 6;
            case "function.builtin", "variable.builtin" -> 5;
            case "keyword", "boolean", "number"        -> 4;
            case "operator"                            -> 3;
            case "function", "method"                  -> 2;
            default                                    -> 1;
        };
    }

    // ── Token type mapping ────────────────────────────────────────────────

    private static byte highlightNameToToken(String name) {
        return switch (name) {
            case "function.builtin",
                 "variable.builtin"  -> Token.KEYWORD2;
            case "keyword"           -> Token.KEYWORD1;
            case "boolean",
                 "nil_literal"       -> Token.KEYWORD1;
            case "function",
                 "method"            -> Token.KEYWORD3;
            case "comment"           -> Token.COMMENT1;
            case "string"            -> Token.LITERAL1;
            case "number"            -> Token.LITERAL2;
            case "operator"          -> Token.OPERATOR;
            case "error"             -> Token.INVALID;
            default                  -> Token.NULL;
        };
    }

    // ── Cleanup ───────────────────────────────────────────────────────────

    public void dispose() {
        scheduler.shutdownNow();
        highlights.set(EMPTY);
        lineOffsets = EMPTY_LINES;
        if (tsQuery != null) {
            tsQuery.close();
            tsQuery = null;
        }
    }
}
