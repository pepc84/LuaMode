package processing.mode.lua;

import processing.app.RunnerListener;
import processing.app.Sketch;

import java.io.File;
import java.util.List;

/**
 * Drives {@link LuaBuild} on a background thread and reports results
 * back to the Processing editor via {@link RunnerListener}.
 *
 * This class owns the thread lifecycle only — no build logic lives here.
 *
 * <p>The {@code onComplete} callback is invoked on the runner thread when
 * the run ends — whether normally, on error, or via {@link #halt()}.
 * {@link LuaEditor} uses this to clear its {@code runner} reference so that
 * lint feedback resumes after a sketch exits naturally.</p>
 */
public final class LuaRunner {

    private final Sketch         sketch;
    private final File           modeFolder;
    private final RunnerListener listener;
    private final Runnable       onComplete;
    private       Thread         thread;

    /**
     * @param onComplete called once when the runner thread finishes (any outcome).
     *                   May be {@code null} if no callback is needed.
     */
    public LuaRunner(Sketch sketch, File modeFolder, RunnerListener listener,
                     Runnable onComplete) {
        this.sketch     = sketch;
        this.modeFolder = modeFolder;
        this.listener   = listener;
        this.onComplete = onComplete != null ? onComplete : () -> {};
    }

    // ── Control ───────────────────────────────────────────────────────────

    public void launch() {
        thread = new Thread(this::doRun, "lua-runner");
        thread.setDaemon(true);
        thread.start();
    }

    public void halt() {
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    // ── Worker ────────────────────────────────────────────────────────────

    private void doRun() {
        RunLog.log("runner thread started");
        try {
            LuaBuild build = new LuaBuild(sketch, modeFolder);
            List<LuaProblem> problems = build.run();

            if (Thread.currentThread().isInterrupted()) return;

            if (problems.isEmpty()) {
                listener.statusNotice("Done.");
            } else {
                LuaProblem first = problems.stream()
                    .filter(LuaProblem::isError)
                    .findFirst()
                    .orElse(problems.get(0));

                listener.statusError(formatProblem(first));

                if (problems.size() > 1) {
                    listener.statusNotice(
                        String.format("%d error%s found.",
                            problems.size(), problems.size() == 1 ? "" : "s"));
                }
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable e) {
            RunLog.log("run failed", e);
            listener.statusError("LuaMode: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Always notify the editor that this runner has finished, so it can
            // clear its reference and re-enable lint feedback.
            onComplete.run();
        }
    }

    private static String formatProblem(LuaProblem p) {
        return String.format("Line %d: %s", p.getLineNumber() + 1, p.getMessage());
    }
}
