package processing.mode.lua;

import ch.usi.si.seart.treesitter.Language;
import ch.usi.si.seart.treesitter.Node;
import ch.usi.si.seart.treesitter.Parser;
import ch.usi.si.seart.treesitter.Tree;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Thread-safe pool of tree-sitter Lua parsers.
 * Mirrors CppMode's TsEngine exactly, but uses Language.LUA.
 */
public final class TsLuaEngine {

    // ── Singleton ─────────────────────────────────────────────────────────
    private static volatile TsLuaEngine INSTANCE;

    public static TsLuaEngine get() {
        if (INSTANCE == null) {
            synchronized (TsLuaEngine.class) {
                if (INSTANCE == null) {
                    INSTANCE = new TsLuaEngine();
                }
            }
        }
        return INSTANCE;
    }

    // ── Pool ──────────────────────────────────────────────────────────────
    private static final int POOL_SIZE = 4;
    private final BlockingQueue<Parser> pool = new ArrayBlockingQueue<>(POOL_SIZE);

    private TsLuaEngine() {
        for (int i = 0; i < POOL_SIZE; i++) {
            pool.add(Parser.getFor(Language.LUA));
        }
    }

    /**
     * Parse Lua source and return a {@link ParseResult}.
     * The caller must close the result when done so the parser is returned to the pool.
     */
    public ParseResult parse(String source) {
        Parser parser;
        try {
            parser = pool.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        Tree tree = parser.parse(source);
        return new ParseResult(source, tree, parser);
    }

    // ── ParseResult ───────────────────────────────────────────────────────
    public final class ParseResult implements AutoCloseable {
        public final String source;
        public final Tree   tree;
        public final Node   root;
        private final Parser parser;

        private ParseResult(String source, Tree tree, Parser parser) {
            this.source = source;
            this.tree   = tree;
            this.root   = tree.getRootNode();
            this.parser = parser;
        }

        @Override
        public void close() {
            pool.offer(parser);
        }
    }
}
