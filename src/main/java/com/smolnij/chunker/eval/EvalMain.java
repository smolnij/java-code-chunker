package com.smolnij.chunker.eval;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.smolnij.chunker.config.PropertiesLoader;
import com.smolnij.chunker.eval.fixture.Fixture;
import com.smolnij.chunker.eval.fixture.FixtureFilter;
import com.smolnij.chunker.eval.fixture.FixtureLoader;
import com.smolnij.chunker.eval.reporter.BaselineDiffReporter;
import com.smolnij.chunker.eval.reporter.EvalRecord;
import com.smolnij.chunker.eval.reporter.JsonSummaryReporter;
import com.smolnij.chunker.eval.reporter.JsonlReporter;
import com.smolnij.chunker.eval.reporter.Reporter;
import com.smolnij.chunker.eval.reporter.SummaryReporter;
import com.smolnij.chunker.eval.result.RunResult;
import com.smolnij.chunker.eval.runner.DryRunRunner;
import com.smolnij.chunker.eval.runner.ModeRunner;
import com.smolnij.chunker.eval.runner.RetrievalRunner;
import com.smolnij.chunker.eval.runner.RunContext;
import com.smolnij.chunker.eval.runner.SafeLoopRunner;
import com.smolnij.chunker.eval.scorer.AnalyzerScorer;
import com.smolnij.chunker.eval.scorer.BuildScorer;
import com.smolnij.chunker.eval.scorer.Metric;
import com.smolnij.chunker.eval.scorer.RetrievalScorer;
import com.smolnij.chunker.eval.scorer.SafeLoopScorer;
import com.smolnij.chunker.eval.scorer.Scorer;
import com.smolnij.chunker.eval.verifier.CompilingVerifier;
import com.smolnij.chunker.eval.result.RetrievedChunk;
import com.smolnij.chunker.eval.verifier.NoopVerifier;
import com.smolnij.chunker.eval.verifier.Verifier;
import com.smolnij.chunker.eval.verifier.VerifierResult;
import com.smolnij.chunker.retrieval.Neo4jGraphReader.CandidateStats;
import com.smolnij.chunker.retrieval.RetrievalConfig;
import com.smolnij.chunker.safeloop.SafeLoopConfig;
import com.smolnij.chunker.util.Errors;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Entry point for the golden-task evaluation harness.
 *
 * <h3>Usage:</h3>
 * <pre>
 *   java -cp java-code-chunker.jar com.smolnij.chunker.eval.EvalMain config/eval.properties
 * </pre>
 */
public final class EvalMain {

    public static final String DEFAULT_FIXTURES_DIR = "eval-fixtures";

    public static void main(String[] args) {
        Instant runStartedAt = Instant.now();
        Properties p = PropertiesLoader.loadOrExit(args, "EvalMain", "config/eval.properties");

        EvalConfig cfg = EvalConfig.fromProperties(p);
        if (cfg.selfCheck) cfg.dryRun = true;

        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║  Golden-Task Evaluation Harness                      ║");
        System.out.println("║  Retrieval + SafeLoop scoring                        ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        printBuildBanner();
        System.out.println();

        List<Fixture> fixtures;
        try {
            Path dir = cfg.fixturesDir != null ? cfg.fixturesDir : Path.of(DEFAULT_FIXTURES_DIR);
            fixtures = FixtureLoader.loadAll(dir);
        } catch (Exception e) {
            System.err.println("ERROR: failed to load fixtures — " + Errors.format(e));
            e.printStackTrace();
            System.exit(1);
            return;
        }

        FixtureFilter filter = new FixtureFilter(cfg.idRegex, cfg.tags, cfg.mode);
        List<Fixture> selected = filter.apply(fixtures);
        if (cfg.limit > 0 && selected.size() > cfg.limit) {
            selected = selected.subList(0, cfg.limit);
        }

        if (selected.isEmpty()) {
            System.err.println("No fixtures matched the filter (" + fixtures.size()
                    + " total, 0 selected). Check eval.fixturesDir / eval.idRegex / eval.tags / eval.mode.");
            System.exit(1);
            return;
        }

        System.out.println("Loaded " + fixtures.size() + " fixtures; running "
                + selected.size() + " after filters.");
        if (cfg.dryRun) System.out.println("Mode: dry-run (no Neo4j, no LLM).");
        if (cfg.debug) printDebugConfig(cfg, selected);
        System.out.println();

        Path outputDir = cfg.outputDir != null ? cfg.outputDir
                : Path.of("eval-results",
                          DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                                  .withZone(java.time.ZoneOffset.UTC)
                                  .format(Instant.now()));

        List<EvalRecord> records;
        try {
            records = cfg.dryRun
                    ? runDryRun(selected, cfg)
                    : runLive(selected, cfg, p);
        } catch (Exception e) {
            System.err.println("ERROR: eval pipeline failed — " + Errors.format(e));
            e.printStackTrace();
            System.exit(1);
            return;
        }

        try {
            writeManifest(outputDir, cfg, selected.size(), fixtures.size(), runStartedAt);
            runReporters(outputDir, cfg, records);
        } catch (IOException e) {
            System.err.println("ERROR: failed to write reports — " + Errors.format(e));
            e.printStackTrace();
            System.exit(1);
            return;
        }

        System.exit(decideExitCode(records, cfg, outputDir));
    }

    // ═══════════════════════════════════════════════════════════════
    // Pipelines
    // ═══════════════════════════════════════════════════════════════

    private static List<EvalRecord> runDryRun(List<Fixture> fixtures, EvalConfig cfg) {
        DryRunRunner runner = new DryRunRunner();
        Verifier verifier = pickVerifier(cfg);
        List<Scorer> scorers = defaultScorers();

        List<EvalRecord> records = new ArrayList<>(fixtures.size());
        for (Fixture f : fixtures) {
            Fixture effective = cfg.retrievalOnly ? withMode(f, "retrieval") : f;
            RunResult res = runWithTimeout(runner, effective, null);
            EvalRecord rec = score(effective, res, verifier, scorers);
            records.add(rec);
            printFixtureLine(effective, res, rec, null);
            if (cfg.debug) printFixtureDebug(effective, rec);
            if (shouldFailFast(cfg, res, rec)) break;
        }
        return records;
    }

    private static List<EvalRecord> runLive(List<Fixture> fixtures, EvalConfig cfg, Properties p) throws Exception {
        RetrievalConfig retrievalConfig = RetrievalConfig.fromProperties(p);
        SafeLoopConfig safeLoopConfig = SafeLoopConfig.fromProperties(p);

        String uri = PropertiesLoader.requireString(p, "neo4j.uri");
        String user = PropertiesLoader.getString(p, "neo4j.user", "neo4j");
        String password = PropertiesLoader.requireString(p, "neo4j.password");

        Verifier verifier = pickVerifier(cfg);
        List<Scorer> scorers = defaultScorers();
        List<EvalRecord> records = new ArrayList<>(fixtures.size());

        try (RunContext ctx = new RunContext(uri, user, password, retrievalConfig, safeLoopConfig)) {
            for (Fixture f : fixtures) {
                Fixture effective = cfg.retrievalOnly ? withMode(f, "retrieval") : f;
                ModeRunner runner = pickRunner(effective.mode());
                RunResult res = runWithTimeout(runner, effective, ctx);
                EvalRecord rec = score(effective, res, verifier, scorers);
                records.add(rec);
                printFixtureLine(effective, res, rec, ctx);
                if (cfg.debug) printFixtureDebug(effective, rec);
                if (shouldFailFast(cfg, res, rec)) break;
            }
        }
        return records;
    }

    private static final int DEFAULT_FIXTURE_TIMEOUT_SECONDS = 600;

    /**
     * Run a fixture under a wall-clock budget so a livelocked LLM/loop can't
     * wedge the whole eval. Honours {@code fixture.timeoutSeconds} when set;
     * falls back to {@value #DEFAULT_FIXTURE_TIMEOUT_SECONDS}s otherwise.
     */
    private static RunResult runWithTimeout(ModeRunner runner, Fixture fixture, RunContext ctx) {
        Integer t = fixture.timeoutSeconds();
        int seconds = (t == null || t <= 0) ? DEFAULT_FIXTURE_TIMEOUT_SECONDS : t;

        Instant startedAt = Instant.now();
        long t0 = System.currentTimeMillis();

        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread th = new Thread(r, "eval-fixture-" + fixture.id());
            th.setDaemon(true);
            return th;
        });
        Future<RunResult> future = exec.submit(() -> runner.run(fixture, ctx));
        try {
            return future.get(seconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            long duration = System.currentTimeMillis() - t0;
            return new RunResult(
                    fixture.id(), runner.modeName(), startedAt, duration,
                    null, List.of(), null,
                    "timeout after " + seconds + "s");
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - t0;
            return new RunResult(
                    fixture.id(), runner.modeName(), startedAt, duration,
                    null, List.of(), null,
                    Errors.format(e));
        } finally {
            exec.shutdownNow();
        }
    }

    private static boolean shouldFailFast(EvalConfig cfg, RunResult res, EvalRecord rec) {
        if (!cfg.failFast) return false;
        if (res.isError()) return true;
        if (!cfg.failFastOnMetric) return false;
        for (Metric m : rec.metrics()) {
            if (Metric.FAIL.equals(m.status()) || Metric.ERROR.equals(m.status())) return true;
        }
        return false;
    }

    private static ModeRunner pickRunner(String mode) {
        return switch (mode.toLowerCase()) {
            case "retrieval" -> new RetrievalRunner();
            case "safeloop" -> new SafeLoopRunner();
            // Don't kill the run when a fixture references a mode we haven't wired
            // — record the failure on that one fixture and let the rest proceed.
            default -> new UnknownModeRunner(mode);
        };
    }

    /**
     * No-op runner used when a fixture's mode is not recognized. Returns a
     * {@link RunResult} carrying the failure as {@code error}, so reporters
     * record the fixture and {@link #decideExitCode} flags it.
     */
    private record UnknownModeRunner(String mode) implements ModeRunner {
        @Override public String modeName() { return mode; }
        @Override public RunResult run(Fixture fixture, RunContext ctx) {
            return new RunResult(
                    fixture.id(), mode, Instant.now(), 0L,
                    null, List.of(), null,
                    "unsupported mode: " + mode);
        }
    }

    private static List<Scorer> defaultScorers() {
        return List.of(new RetrievalScorer(), new AnalyzerScorer(), new SafeLoopScorer(), new BuildScorer());
    }

    private static Verifier pickVerifier(EvalConfig cfg) {
        String choice = cfg.verifier == null ? "noop" : cfg.verifier.trim().toLowerCase();
        return switch (choice) {
            case "compiling", "compile" -> new CompilingVerifier();
            default -> new NoopVerifier();
        };
    }

    private static EvalRecord score(Fixture f, RunResult res, Verifier verifier, List<Scorer> scorers) {
        VerifierResult compile = verifier.verifyCompile(f, res);
        VerifierResult tests = verifier.verifyTests(f, res);
        List<Metric> metrics = new ArrayList<>();
        for (Scorer s : scorers) metrics.addAll(s.score(f, res, compile, tests));
        return new EvalRecord(f, res, compile, tests, metrics);
    }

    private static Fixture withMode(Fixture f, String mode) {
        return new Fixture(
                f.schemaVersion(), f.id(), f.description(), mode, f.query(),
                f.topK(), f.gold(), f.expected(), f.tags(), f.timeoutSeconds());
    }

    // ═══════════════════════════════════════════════════════════════
    // Reporting
    // ═══════════════════════════════════════════════════════════════

    private static void writeManifest(Path outDir, EvalConfig cfg, int runFixtures, int totalFixtures,
                                      Instant runStartedAt) throws IOException {
        Files.createDirectories(outDir);
        JsonObject root = new JsonObject();
        root.addProperty("startedAt", runStartedAt.toString());
        root.addProperty("finishedAt", Instant.now().toString());
        root.addProperty("totalFixtures", totalFixtures);
        root.addProperty("runFixtures", runFixtures);
        root.addProperty("dryRun", cfg.dryRun);
        root.addProperty("selfCheck", cfg.selfCheck);
        root.addProperty("retrievalOnly", cfg.retrievalOnly);
        root.addProperty("failFast", cfg.failFast);
        root.addProperty("limit", cfg.limit);
        if (cfg.fixturesDir != null) root.addProperty("fixturesDir", cfg.fixturesDir.toString());
        if (cfg.baselineJsonl != null) root.addProperty("baseline", cfg.baselineJsonl.toString());
        root.addProperty("diffEpsilon", cfg.diffEpsilon);
        if (cfg.idRegex != null) root.addProperty("idRegex", cfg.idRegex);
        if (cfg.mode != null) root.addProperty("mode", cfg.mode);
        JsonArray tagArr = new JsonArray();
        for (String t : cfg.tags) tagArr.add(t);
        root.add("tags", tagArr);
        Files.writeString(outDir.resolve("manifest.json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(root));
    }

    private static void runReporters(Path outDir, EvalConfig cfg, List<EvalRecord> records)
            throws IOException {
        // JsonlReporter is the source of truth — failure here aborts. Every other
        // reporter is best-effort: a broken baseline path or summary file should not
        // lose the JSONL or the manifest.
        new JsonlReporter().write(outDir, records);

        List<Reporter> bestEffort = new ArrayList<>();
        bestEffort.add(new SummaryReporter());
        bestEffort.add(new JsonSummaryReporter());
        if (cfg.baselineJsonl != null) {
            bestEffort.add(new BaselineDiffReporter(cfg.baselineJsonl, cfg.diffEpsilon));
        }
        for (Reporter r : bestEffort) {
            try {
                r.write(outDir, records);
            } catch (Exception e) {
                System.err.println("WARN: reporter " + r.getClass().getSimpleName()
                        + " failed — " + Errors.format(e));
            }
        }
        System.out.println();
        System.out.println("✓ Results written to " + outDir.toAbsolutePath());
    }

    // Distinct exit codes so CI can branch on the failure mode:
    //   0 — success
    //   1 — at least one fixture errored during execution
    //   2 — empty graph (every live fixture returned 0 chunks)
    //   3 — self-check FAIL/ERROR metrics
    //   4 — regression vs baseline
    static final int EXIT_OK = 0;
    static final int EXIT_FIXTURE_ERROR = 1;
    static final int EXIT_EMPTY_GRAPH = 2;
    static final int EXIT_SELF_CHECK_FAILED = 3;
    static final int EXIT_REGRESSION = 4;

    private static int decideExitCode(List<EvalRecord> records, EvalConfig cfg, Path outDir) {
        int errors = 0;
        StringBuilder errorDetail = new StringBuilder();
        for (EvalRecord rec : records) {
            if (rec.result().isError()) {
                errors++;
                errorDetail.append("  ").append(rec.fixture().id())
                           .append(" — ").append(rec.result().error()).append('\n');
            }
        }

        boolean emptyGraph = !cfg.dryRun && records.stream()
                .allMatch(r -> r.result().retrieved().isEmpty() && !r.result().isError());
        if (emptyGraph) {
            System.err.println();
            System.err.println("EXIT " + EXIT_EMPTY_GRAPH + ": every fixture returned 0 retrieved chunks.");
            System.err.println("        Neo4j graph appears empty. Run ChunkerMain on the target repo first.");
            return EXIT_EMPTY_GRAPH;
        }

        if (cfg.selfCheck) {
            int failed = 0;
            StringBuilder sb = new StringBuilder();
            for (EvalRecord rec : records) {
                for (Metric m : rec.metrics()) {
                    if (Metric.FAIL.equals(m.status()) || Metric.ERROR.equals(m.status())) {
                        failed++;
                        sb.append("  ").append(rec.fixture().id()).append(" [")
                          .append(m.name()).append("] status=").append(m.status())
                          .append(" note=").append(m.note()).append('\n');
                    }
                }
            }
            if (failed > 0) {
                System.err.println();
                System.err.println("EXIT " + EXIT_SELF_CHECK_FAILED
                        + ": self-check failed (" + failed + " FAIL/ERROR metrics)");
                System.err.print(sb);
                return EXIT_SELF_CHECK_FAILED;
            }
            System.out.println("✓ self-check passed");
        }

        if (errors > 0) {
            System.err.println();
            System.err.println("EXIT " + EXIT_FIXTURE_ERROR + ": " + errors + " of " + records.size()
                    + " fixture(s) errored during execution:");
            System.err.print(errorDetail);
            return EXIT_FIXTURE_ERROR;
        }
        if (cfg.baselineJsonl != null && regressionDetected(outDir)) {
            System.err.println();
            System.err.println("EXIT " + EXIT_REGRESSION + ": regression detected vs baseline "
                    + cfg.baselineJsonl
                    + " (see " + outDir.resolve(BaselineDiffReporter.JSON_FILENAME) + ")");
            return EXIT_REGRESSION;
        }
        return EXIT_OK;
    }

    private static boolean regressionDetected(Path outDir) {
        Path diff = outDir.resolve(BaselineDiffReporter.JSON_FILENAME);
        if (!Files.isRegularFile(diff)) return false;
        try {
            String json = Files.readString(diff);
            JsonObject o = new com.google.gson.Gson().fromJson(json, JsonObject.class);
            return o.has("regressions") && o.getAsJsonArray("regressions").size() > 0;
        } catch (Exception e) {
            // Don't silently bypass regression checking — a malformed diff.json means
            // the diff reporter itself is broken; surface it so CI doesn't go green by mistake.
            System.err.println("WARN: could not parse " + diff + " for regression check — "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Print the running JAR's path, mtime, and the repo's git short SHA so a
     * stale fat-JAR (caller forgot {@code mvn package}) is impossible to miss.
     * All discovery is best-effort — failures are swallowed to a single dash.
     */
    private static void printBuildBanner() {
        String jar = "?";
        String mtime = "?";
        try {
            java.net.URL src = EvalMain.class.getProtectionDomain().getCodeSource().getLocation();
            Path path = Path.of(src.toURI());
            jar = path.toString();
            mtime = Files.getLastModifiedTime(path).toInstant().toString();
        } catch (Exception ignored) {}
        String git = "?";
        try {
            Process p = new ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                    .redirectErrorStream(true).start();
            if (p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                git = new String(p.getInputStream().readAllBytes()).trim();
                if (git.isEmpty()) git = "?";
            } else {
                p.destroy();
            }
        } catch (Exception ignored) {}
        System.out.println("EvalMain build: jar=" + jar + " mtime=" + mtime + " git=" + git);
    }

    private static void printDebugConfig(EvalConfig cfg, List<Fixture> selected) {
        System.out.println();
        System.out.println("── Debug: Resolved Config ──────────────────────────────");
        System.out.println("  fixturesDir   = " + (cfg.fixturesDir == null ? "(default eval-fixtures)" : cfg.fixturesDir));
        System.out.println("  outputDir     = " + (cfg.outputDir == null ? "(auto eval-results/<ts>)" : cfg.outputDir));
        System.out.println("  baselineJsonl = " + cfg.baselineJsonl);
        System.out.println("  diffEpsilon   = " + cfg.diffEpsilon);
        System.out.println("  idRegex       = " + cfg.idRegex);
        System.out.println("  tags          = " + cfg.tags);
        System.out.println("  mode          = " + cfg.mode);
        System.out.println("  dryRun        = " + cfg.dryRun);
        System.out.println("  selfCheck     = " + cfg.selfCheck);
        System.out.println("  retrievalOnly = " + cfg.retrievalOnly);
        System.out.println("  failFast      = " + cfg.failFast);
        System.out.println("  limit         = " + cfg.limit);
        System.out.println("  verifier      = " + cfg.verifier);
        System.out.println();
        System.out.println("── Debug: Selected Fixtures (" + selected.size() + ") ───────────────");
        for (Fixture f : selected) {
            System.out.println("  " + f.id() + "  mode=" + f.mode()
                    + "  topK=" + f.topK()
                    + "  tags=" + f.tags());
        }
    }

    private static void printFixtureDebug(Fixture f, EvalRecord rec) {
        RunResult res = rec.result();
        System.out.println("        ── debug ──────────────────────────────────────────");
        System.out.println("        query: " + f.query());
        System.out.println("        anchorId: " + res.anchorId());
        if (f.gold() != null) {
            System.out.println("        gold.anchor:   " + f.gold().anchor());
            System.out.println("        gold.relevant: " + f.gold().relevant());
        }
        System.out.println("        retrieved (" + res.retrieved().size() + "):");
        for (RetrievedChunk c : res.retrieved()) {
            System.out.printf("          #%-2d  score=%.4f  %s%n", c.rank(), c.score(), c.chunkId());
        }
        System.out.println("        metrics:");
        for (Metric m : rec.metrics()) {
            System.out.printf("          [%s] %-32s value=%.4f  note=%s%n",
                    m.status(), m.name(), m.value(), m.note() == null ? "" : m.note());
        }
        if (rec.compile() != null) {
            System.out.println("        compile: " + rec.compile().status() + "  note=" + rec.compile().note());
        }
        if (rec.tests() != null) {
            System.out.println("        tests:   " + rec.tests().status() + "  note=" + rec.tests().note());
        }
        if (res.isError()) {
            System.out.println("        error: " + res.error());
        }
        System.out.println();
    }

    private static void printFixtureLine(Fixture f, RunResult res, EvalRecord rec, RunContext ctx) {
        int pass = 0, fail = 0, err = 0, notRun = 0;
        for (Metric m : rec.metrics()) {
            switch (m.status()) {
                case Metric.PASS -> pass++;
                case Metric.FAIL -> fail++;
                case Metric.ERROR -> err++;
                case Metric.NOT_RUN -> notRun++;
            }
        }
        String status = res.isError() ? "ERR " : (fail + err > 0 ? "FAIL" : "OK  ");
        System.out.printf("  [%s] %-40s  mode=%-10s  pass=%d fail=%d err=%d n/r=%d  (%dms)%n",
                status, f.id(), f.mode(), pass, fail, err, notRun, res.durationMs());
        if (res.isError()) System.out.println("        error: " + res.error());
        // Snapshot the resolver's per-candidate stats while the reader still holds them.
        // Subsequent fixtures will overwrite the reader's lastFallbackStats, so we have
        // to capture before moving on. Empty in dry-run / non-trace.
        List<CandidateStats> stats = (ctx == null)
                ? List.of() : ctx.reader().getLastFallbackStats();
        emitAnchorMismatchTrace(f, res, stats);
    }

    /**
     * Emit grep-friendly trace lines for the three diagnosable retrieval failure
     * shapes, so debugging doesn't require scrolling through ranking dumps:
     * <ul>
     *   <li>{@code [trace] retrieval.empty} — resolver/expansion produced 0 chunks.</li>
     *   <li>{@code [trace] anchor.unresolved} — gold anchor known but resolver returned null.</li>
     *   <li>{@code [trace] anchor.mismatch} — resolver picked the wrong chunk; reports
     *       both ranks and a best-effort {@code reason}.</li>
     * </ul>
     */
    private static void emitAnchorMismatchTrace(Fixture f, RunResult res, List<CandidateStats> stats) {
        if (f.gold() == null) return;
        String gold = f.gold().anchor();
        boolean haveGoldAnchor = gold != null && !gold.isBlank();
        boolean haveGoldRelevant = !f.gold().relevant().isEmpty();
        String picked = res.anchorId();

        // B1: any non-error fixture with gold but zero retrieval is a silent zero today.
        if (res.retrieved().isEmpty() && (haveGoldAnchor || haveGoldRelevant) && !res.isError()) {
            String q = f.query() == null ? "" : f.query();
            if (q.length() > 80) q = q.substring(0, 77) + "…";
            System.out.printf("        [trace] retrieval.empty fixture=%s anchor=%s query=\"%s\"%n",
                    f.id(), picked == null ? "null" : picked, q);
        }

        if (!haveGoldAnchor) return;

        // B2: resolver returned null for a fixture whose gold.anchor is known.
        if (picked == null) {
            System.out.printf("        [trace] anchor.unresolved fixture=%s gold=%s retrieved.size=%d reason=resolver-returned-null%n",
                    f.id(), gold, res.retrieved().size());
            return;
        }
        // Normalize using the same rules the metric scorer applies (drop #partN, then
        // strip the parameter list for a loose match). Without this the trace contradicts
        // the metric — e.g. picked=Foo#run(String)#part1 vs gold=Foo#run(String) reports
        // anchor.mismatch even though retrieval.anchor.hit PASSes.
        String pickedNorm = RetrievalScorer.stripPartSuffix(picked);
        String goldNorm = RetrievalScorer.stripPartSuffix(gold);
        if (goldNorm.equals(pickedNorm)) return;
        if (RetrievalScorer.stripParamList(goldNorm).equals(RetrievalScorer.stripParamList(pickedNorm))) return;

        int pickedRank = -1;
        int goldRank = -1;
        for (RetrievedChunk c : res.retrieved()) {
            String cid = RetrievalScorer.stripPartSuffix(c.chunkId());
            if (pickedRank < 0 && cid.equals(pickedNorm)) pickedRank = c.rank();
            if (goldRank < 0 && cid.equals(goldNorm)) goldRank = c.rank();
        }
        // Loose-match fallback for goldRank so param-drift doesn't read as not-in-topK.
        if (goldRank < 0) {
            String goldLoose = RetrievalScorer.stripParamList(goldNorm);
            for (RetrievedChunk c : res.retrieved()) {
                String cid = RetrievalScorer.stripParamList(RetrievalScorer.stripPartSuffix(c.chunkId()));
                if (cid.equals(goldLoose)) {
                    goldRank = c.rank();
                    break;
                }
            }
        }
        String pickedRankStr = pickedRank > 0 ? String.valueOf(pickedRank) : "n/a";
        String goldRankStr = goldRank > 0 ? String.valueOf(goldRank) : "not-in-topK";

        // Heuristic reason. The most common signal we have without re-instrumenting
        // the resolver is "gold and picked share a class name segment" — that points
        // at the CONTAINS @class-boundary fan-in tie-break. Otherwise it's a generic
        // anchor mismatch (likely vector-fallback drift).
        String reason;
        String pickedClass = classSegment(picked);
        String goldClass = classSegment(gold);
        if (!pickedClass.isEmpty() && pickedClass.equals(goldClass)) {
            reason = "fallback-fan-in";
        } else if (goldRank < 0) {
            reason = "gold-not-retrieved";
        } else {
            reason = "anchor-mismatch";
        }

        System.out.printf("        [trace] anchor.mismatch fixture=%s picked=%s gold=%s picked.rank=%s gold.rank=%s reason=%s%n",
                f.id(), picked, gold, pickedRankStr, goldRankStr, reason);

        // [trace] anchor.delta — pairs picked + gold with their CONTAINS-fallback
        // pool stats (semSim, tokens, extFanIn, nameOverlap). Only emitted when the
        // resolver actually went through the CONTAINS-fallback path, i.e. when stats
        // are populated. Looks up by exact chunkId first, then loose match (drop
        // #partN suffix and parameter list) so e.g. picked="X#part1" pairs with the
        // gold's underlying method.
        if (stats != null && !stats.isEmpty()) {
            CandidateStats pickedStats = lookupStats(stats, picked);
            CandidateStats goldStats = lookupStats(stats, gold);
            if (pickedStats != null || goldStats != null) {
                System.out.printf("        [trace] anchor.delta fixture=%s picked=%s gold=%s%n",
                        f.id(), picked, gold);
                System.out.println("            picked: " + formatStats(pickedStats));
                System.out.println("            gold:   " + formatStats(goldStats));
                if (pickedStats != null && goldStats != null
                        && !Double.isNaN(pickedStats.semSim()) && !Double.isNaN(goldStats.semSim())) {
                    System.out.printf("            Δsem=%+.4f  ΔnameOverlap=%+d  Δtokens=%+d  ΔextFanIn=%+d%n",
                            pickedStats.semSim() - goldStats.semSim(),
                            pickedStats.nameOverlap() - goldStats.nameOverlap(),
                            pickedStats.tokens() - goldStats.tokens(),
                            pickedStats.extFanIn() - goldStats.extFanIn());
                } else if (goldStats == null) {
                    System.out.println("            note: gold not in CONTAINS-fallback pool — embedding-text or chunking defect, not a ranking-policy bug");
                }
            }
        }
    }

    private static CandidateStats lookupStats(List<CandidateStats> stats, String chunkId) {
        if (chunkId == null) return null;
        for (CandidateStats c : stats) if (chunkId.equals(c.chunkId())) return c;
        // Loose match: drop #partN, then drop parameter list. Same normalization
        // RetrievalScorer uses for anchor.hit, so the trace agrees with the metric.
        String norm = com.smolnij.chunker.eval.scorer.RetrievalScorer.stripParamList(
                com.smolnij.chunker.eval.scorer.RetrievalScorer.stripPartSuffix(chunkId));
        for (CandidateStats c : stats) {
            String cn = com.smolnij.chunker.eval.scorer.RetrievalScorer.stripParamList(
                    com.smolnij.chunker.eval.scorer.RetrievalScorer.stripPartSuffix(c.chunkId()));
            if (norm.equals(cn)) return c;
        }
        return null;
    }

    private static String formatStats(CandidateStats c) {
        if (c == null) return "(not in CONTAINS pool)";
        String sem = Double.isNaN(c.semSim()) ? "n/a" : String.format("%.4f", c.semSim());
        return String.format("semSim=%s tokens=%d extFanIn=%d fanIn=%d nameOverlap=%d (%s)",
                sem, c.tokens(), c.extFanIn(), c.fanIn(), c.nameOverlap(), c.nameOverlapTerms());
    }

    private static String classSegment(String chunkId) {
        if (chunkId == null) return "";
        int hash = chunkId.indexOf('#');
        String head = hash > 0 ? chunkId.substring(0, hash) : chunkId;
        int lastDot = head.lastIndexOf('.');
        return lastDot >= 0 ? head.substring(lastDot + 1) : head;
    }

    private EvalMain() {}
}
