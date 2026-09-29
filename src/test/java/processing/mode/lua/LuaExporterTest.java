package processing.mode.lua;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the static helpers in {@link LuaExporter} that do not
 * require the Processing IDE runtime (no {@code Sketch} instantiation needed).
 *
 * The export methods themselves depend on {@code processing.app.Sketch} (a
 * {@code compileOnly} dependency) and are exercised by integration/manual
 * testing.  These tests cover the file-I/O and template assembly logic that
 * can be reached via package-private access and the {@code copyDirectory}
 * helper.
 */
class LuaExporterTest {

    // ── copyDirectory ─────────────────────────────────────────────────────────

    @Test
    void copyDirectory_copiesFilesRecursively(@TempDir Path tmp) throws IOException {
        // Build a source tree:  src/a.txt, src/sub/b.txt
        File src = tmp.resolve("src").toFile();
        File sub = new File(src, "sub");
        sub.mkdirs();
        Files.writeString(new File(src, "a.txt").toPath(), "hello", StandardCharsets.UTF_8);
        Files.writeString(new File(sub, "b.txt").toPath(), "world", StandardCharsets.UTF_8);

        File dest = tmp.resolve("dst").toFile();
        LuaExporter.testCopyDirectory(src, dest);

        assertTrue(new File(dest, "a.txt").exists(),       "a.txt not copied");
        assertTrue(new File(dest, "sub/b.txt").exists(),   "sub/b.txt not copied");
        assertEquals("hello",
            Files.readString(new File(dest, "a.txt").toPath(), StandardCharsets.UTF_8));
        assertEquals("world",
            Files.readString(new File(dest, "sub/b.txt").toPath(), StandardCharsets.UTF_8));
    }

    @Test
    void copyDirectory_missingSrcIsNoop(@TempDir Path tmp) throws IOException {
        File src  = tmp.resolve("nonexistent").toFile();
        File dest = tmp.resolve("dst").toFile();
        // Should not throw
        assertDoesNotThrow(() -> LuaExporter.testCopyDirectory(src, dest));
        assertFalse(dest.exists(), "dest should not be created when src is missing");
    }

    @Test
    void copyDirectory_overwritesExistingFile(@TempDir Path tmp) throws IOException {
        File src  = tmp.resolve("src").toFile();
        File dest = tmp.resolve("dst").toFile();
        src.mkdirs();
        dest.mkdirs();

        Files.writeString(new File(src, "f.txt").toPath(),  "new", StandardCharsets.UTF_8);
        Files.writeString(new File(dest, "f.txt").toPath(), "old", StandardCharsets.UTF_8);

        LuaExporter.testCopyDirectory(src, dest);

        assertEquals("new",
            Files.readString(new File(dest, "f.txt").toPath(), StandardCharsets.UTF_8),
            "Existing file should be overwritten");
    }

    // ── appendTabs separator format ───────────────────────────────────────────
    //
    // appendTabs writes "-- === <PrettyName> ===\n\n" before each non-blank tab.
    // We verify this via testBuildTabSource, which is a direct call to the logic.

    @Test
    void tabSeparator_format() {
        String result = LuaExporter.testBuildTabSource(
            new String[]{"Alpha", "Beta"},
            new String[]{"x = 1\n", "y = 2\n"}
        );
        assertTrue(result.contains("-- === Alpha ==="), "Alpha separator missing");
        assertTrue(result.contains("-- === Beta ==="),  "Beta separator missing");
        int posAlpha = result.indexOf("-- === Alpha ===");
        int posBeta  = result.indexOf("-- === Beta ===");
        assertTrue(posAlpha < posBeta, "Alpha should appear before Beta");
    }

    @Test
    void tabSeparator_blankTabSkipped() {
        String result = LuaExporter.testBuildTabSource(
            new String[]{"Main", "Empty", "Other"},
            new String[]{"code()\n", "   \n", "more()\n"}
        );
        assertFalse(result.contains("-- === Empty ==="), "Blank tab separator should be skipped");
        assertTrue(result.contains("-- === Main ==="));
        assertTrue(result.contains("-- === Other ==="));
    }

    @Test
    void tabSeparator_nullProgSkipped() {
        String result = LuaExporter.testBuildTabSource(
            new String[]{"Main", "Null"},
            new String[]{"code()\n", null}
        );
        assertFalse(result.contains("-- === Null ==="), "Null-program tab should be skipped");
    }

    @Test
    void tabSource_trailingNewlineAdded() {
        // A tab without a trailing \n should have one appended
        String result = LuaExporter.testBuildTabSource(
            new String[]{"T"},
            new String[]{"no_newline"}
        );
        assertTrue(result.endsWith("\n"), "Source should end with a newline");
    }

    @Test
    void tabSource_multipleTabsOrdering() {
        String result = LuaExporter.testBuildTabSource(
            new String[]{"First", "Second", "Third"},
            new String[]{"a = 1\n", "b = 2\n", "c = 3\n"}
        );
        int posA = result.indexOf("a = 1");
        int posB = result.indexOf("b = 2");
        int posC = result.indexOf("c = 3");
        assertTrue(posA < posB && posB < posC, "Tabs should appear in order");
    }

    // ── buildLineOffsets (via TsLuaTokenMarker) is tested separately ─────────
    // ── Roblox / LOVE2D end-to-end templates are tested by integration examples
}
