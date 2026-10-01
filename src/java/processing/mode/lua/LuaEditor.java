package processing.mode.lua;

import processing.app.Base;
import processing.app.syntax.JEditTextArea;
import processing.app.syntax.PdeTextArea;
import processing.app.syntax.PdeTextAreaDefaults;
import processing.app.ui.Editor;
import processing.app.ui.EditorState;
import processing.app.ui.EditorToolbar;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.util.List;

public class LuaEditor extends Editor {

    private final LuaMode         luaMode;
    private volatile LuaRunner    runner;
    private TsLuaTokenMarker      tokenMarker;

    public LuaEditor(Base base, String path, EditorState state, LuaMode mode)
            throws Exception {
        super(base, path, state, mode);
        this.luaMode = mode;
        installTokenMarker();
    }

    // ── Text area ─────────────────────────────────────────────────────────
    //
    // Editor's default is a bare JEditTextArea: no gutter (so no line numbers)
    // and a PdeInputHandler that expects a PdeTextArea, which is why Enter
    // misbehaved. Same setup as Java mode, with Lua-aware typing on top.
    // Called from Editor's constructor, before our own fields are set.

    @Override
    protected JEditTextArea createTextArea() {
        return new PdeTextArea(new PdeTextAreaDefaults(getMode()), new LuaInputHandler(this), this);
    }

    // ── Token marker ──────────────────────────────────────────────────────

    private void installTokenMarker() {
        tokenMarker = new TsLuaTokenMarker();
        tokenMarker.setLintConsumer(this::onLintResults);
        // highlights are rebuilt off the EDT; repaint once they're ready
        tokenMarker.setRepaintHook(() -> javax.swing.SwingUtilities.invokeLater(
            () -> getTextArea().getPainter().repaint()));
        processing.app.syntax.SyntaxDocument doc =
            (processing.app.syntax.SyntaxDocument) getTextArea().getDocument();
        doc.setTokenMarker(tokenMarker);
        doc.addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { invalidateHighlights(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { invalidateHighlights(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { invalidateHighlights(); }
        });
        invalidateHighlights();
    }

    private void onLintResults(List<LuaProblem> problems) {
        javax.swing.SwingUtilities.invokeLater(() -> showLintProblems(problems));
    }

    private void showLintProblems(List<LuaProblem> problems) {
        if (runner != null) return;
        if (problems.isEmpty()) { statusEmpty(); return; }
        LuaProblem first = problems.stream()
            .filter(LuaProblem::isError).findFirst().orElse(problems.get(0));
        String msg = (first.isError() ? "" : "Warning: ") +
            "Line " + (first.getLineNumber() + 1) + ": " + first.getMessage();
        if (first.isError()) statusError(msg); else statusNotice(msg);
    }

    void invalidateHighlights() {
        if (tokenMarker != null) {
            tokenMarker.invalidate(
                (processing.app.syntax.SyntaxDocument) getTextArea().getDocument());
        }
    }

    // ── Abstract Editor methods ───────────────────────────────────────────

    @Override public String getCommentPrefix() { return "--"; }

    @Override
    public void internalCloseRunner() {
        stopCurrentRunner();
    }

    @Override
    public void deactivateRun() {
        stopCurrentRunner();
    }

    // Real menus: Editor adds the standard items (Import Library etc.) through
    // the protected buildXMenu(JMenuItem[]) overloads. An empty JMenu here is
    // what caused the removeImportMenu NPE.
    @Override
    public JMenu buildFileMenu() {
        JMenuItem roblox = new JMenuItem("Export for Roblox...");
        roblox.addActionListener(this::handleRobloxExport);
        JMenuItem love2d = new JMenuItem("Export for LOVE2D...");
        love2d.addActionListener(this::handleLove2dExport);
        try {
            return buildFileMenu(new JMenuItem[] { roblox, love2d });
        } catch (Throwable t) {
            System.err.println("[LuaMode] File menu fallback: " + t);
            JMenu file = new JMenu("File");
            file.add(roblox);
            file.add(love2d);
            return file;
        }
    }

    @Override
    public JMenu buildSketchMenu() {
        JMenuItem run = new JMenuItem("Run");
        run.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_R,
            java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        run.addActionListener(e -> getToolbar().handleRun(0));
        JMenuItem stop = new JMenuItem("Stop");
        stop.addActionListener(e -> handleStop());
        try {
            // sets Editor's private sketchMenu field, which windowActivated needs
            return buildSketchMenu(new JMenuItem[] { run, stop });
        } catch (Throwable t) {
            System.err.println("[LuaMode] Sketch menu fallback: " + t);
            JMenu sketchMenu = new JMenu("Sketch");
            sketchMenu.add(run);
            sketchMenu.add(stop);
            return sketchMenu;
        }
    }

    @Override
    public JMenu buildHelpMenu() {
        JMenu help = new JMenu("Help");
        JMenuItem site = new JMenuItem("Lua Mode on GitHub");
        site.addActionListener(e -> openUrl("https://github.com/processing-cpp/processing-lua"));
        help.add(site);
        JMenuItem ref = new JMenuItem("Lua 5.4 Reference Manual");
        ref.addActionListener(e -> openUrl("https://www.lua.org/manual/5.4/"));
        help.add(ref);
        return help;
    }
    @Override public void handleImportLibrary(String lib) {}
    @Override public processing.app.Formatter createFormatter() { return null; }

    // ── Run / Stop ────────────────────────────────────────────────────────

    /** Called by the toolbar's handleRun(int mode). mode 0=Run, 1=Present. */
    void startRunner(boolean present) {
        RunLog.log("Run pressed (present=" + present + ") sketch=" + sketch.getName()
            + " folder=" + sketch.getFolder());
        stopCurrentRunner();
        statusEmpty();
        LuaRunner[] ref = {null};
        LuaRunner thisRunner = new LuaRunner(sketch, luaMode.getFolder(), this,
            () -> onRunnerFinished(ref[0]));
        ref[0] = thisRunner;
        runner  = thisRunner;
        thisRunner.launch();
    }

    private void onRunnerFinished(LuaRunner finished) {
        if (runner == finished) runner = null;
    }

    public void handleStop() {
        stopCurrentRunner();
        statusNotice("Stopped.");
    }

    private void stopCurrentRunner() {
        if (runner != null) { runner.halt(); runner = null; }
    }

    // ── Toolbar ───────────────────────────────────────────────────────────

    @Override
    public EditorToolbar createToolbar() {
        return new EditorToolbar(this) {
            @Override public void handleRun(int mode) { LuaEditor.this.startRunner(mode == 1); }
            @Override public void handleStop() { LuaEditor.this.handleStop(); }
        };
    }

    // ── Export menu ───────────────────────────────────────────────────────


    private void handleRobloxExport(ActionEvent e) {
        JFileChooser fc = new JFileChooser(sketch.getFolder());
        fc.setSelectedFile(new File(sketch.getFolder(), sketch.getName() + "_roblox.lua"));
        fc.setDialogTitle("Export for Roblox (save as LocalScript)");
        if (fc.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return;
        File dest = fc.getSelectedFile();
        try {
            LuaExporter.exportRoblox(sketch, dest);
            statusNotice("Exported to " + dest.getName());
        } catch (Exception ex) {
            statusError("Export failed: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    private void handleLove2dExport(ActionEvent e) {
        JFileChooser fc = new JFileChooser(sketch.getFolder().getParentFile());
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setSelectedFile(new File(sketch.getFolder().getParentFile(),
                                    sketch.getName() + "-love2d"));
        fc.setDialogTitle("Export for LOVE2D (choose output folder)");
        if (fc.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return;
        File dest = fc.getSelectedFile();
        try {
            LuaExporter.exportLove2d(sketch, dest, luaMode.getFolder());
            statusNotice("Exported to " + dest.getName() + "/");
        } catch (Exception ex) {
            statusError("Export failed: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────

    @Override
    public void dispose() {
        stopCurrentRunner();
        if (tokenMarker != null) { tokenMarker.dispose(); tokenMarker = null; }
        super.dispose();
    }

    private static void openUrl(String url) {
        new Thread(() -> {
            try {
                if (System.getProperty("os.name", "").toLowerCase().contains("linux")) {
                    new ProcessBuilder("xdg-open", url).start();
                } else {
                    java.awt.Desktop.getDesktop().browse(new java.net.URI(url));
                }
            } catch (Exception ex) {
                System.err.println("[LuaMode] couldn't open " + url + ": " + ex);
            }
        }, "luamode-open-url").start();
    }
}
