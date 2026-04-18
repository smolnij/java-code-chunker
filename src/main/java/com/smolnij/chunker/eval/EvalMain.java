package com.smolnij.chunker.eval;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
import com.smolnij.chunker.eval.scorer.Scorer;
import com.smolnij.chunker.eval.verifier.NoopVerifier;
import com.smolnij.chunker.eval.verifier.Verifier;
import com.smolnij.chunker.eval.verifier.VerifierResult;
import com.smolnij.chunker.retrieval.RetrievalConfig;
import com.smolnij.chunker.safeloop.SafeLoopConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Entry point for the golden-task evaluation harness (roadmap item N-8).
 *
 * <p>Runs fixtures through either a retrieval-only or a SafeLoop runner,
 * scores the outputs against a labelled gold set, and writes a
 * machine-readable baseline + human summary to an output directory.
 *
 * <h3>Usage:</h3>
 * <pre>
 *   java -cp java-code-chunker.jar com.smolnij.chunker.eval.EvalMain [options]
 *
 *   --fixtures-dir &lt;path&gt;    override default {@code eval-fixtures/}
 *   --id &lt;regex&gt;             include only matching fixture ids
 *   --tag &lt;tag&gt;              include only fixtures with tag (repeatable)
 *   --mode &lt;mode&gt;            retrieval | safeloop
 *   --output-dir &lt;path&gt;      default: eval-results/&lt;UTC-timestamp&gt;/
 *   --baseline &lt;jsonl&gt;       produce regression diff vs prior runs.jsonl
 *   --diff-epsilon &lt;d&gt;       default 0.05
 *   --dry-run                skip Neo4j + LLM; synthesize RunResult from gold
 *   --self-check             --dry-run + assert every metric is PASS/NOT_RUN
 *   --retrieval-only         force every fixture to mode=retrieval
 *   --limit &lt;n&gt;              first-N fixtures after filtering
 *   --fail-fast              exit nonzero on first fixture error
 *   --debug
 * </pre>
 */
public final class EvalMain {

    public static final String NEO4J_DEFAULT_URL = "bolt://localhost:7687";
    public static final String NEO4J_DEFAULT_USER = "neo4j";
    public static final String NEO4J_DEFAULT_PASSWORD = "12345678";
    public static final String DEFAULT_FIXTURES_DIR = "eval-fixtures";

    public static void main(String[] args) {
        EvalConfig cfg = parseArgs(args);
        if (cfg.selfCheck) cfg.dryRun = true;

        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║  Golden-Task Evaluation Harness                      ║");
        System.out.println("║  Retrieval + SafeLoop scoring                        ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.println();

        List<Fixture> fixtures;
        try {
            Path dir = cfg.fixturesDir != null ? cfg.fixturesDir : Path.of(DEFAULT_FIXTURES_DIR);
            fixtures = FixtureLoader.loadAll(dir);
        } catch (Exception e) {
            System.err.println("ERROR: failed to load fixtures — " + e.getMessage());
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
                    + " total, 0 selected). Check --fixtures-dir / --id / --tag / --mode.");
            System.exit(1);
            return;
        }

        System.out.println("Loaded " + fixtures.size() + " fixtures; running "
                + selected.size() + " after filters.");
        if (cfg.dryRun) System.out.println("Mode: dry-run (no Neo4j, no LLM).");
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
                    : runLive(selected, cfg);
        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
            return;
        }

        try {
            writeManifest(outputDir, cfg, selected.size(), fixtures.size());
            runReporters(outputDir, cfg, records);
        } catch (IOException e) {
            System.err.println("ERROR: failed to write reports — " + e.getMessage());
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
        Verifier verifier = new NoopVerifier();
        List<Scorer> scorers = defaultScorers();

        List<EvalRecord> records = new ArrayList<>(fixtures.size());
        for (Fixture f : fixtures) {
            Fixture effective = cfg.retrievalOnly ? withMode(f, "retrieval") : f;
            RunResult res = runner.run(effective, null);
            records.add(score(effective, res, verifier, scorers));
            printFixtureLine(effective, res, records.get(records.size() - 1));
            if (cfg.failFast && res.isError()) break;
        }
        return records;
    }

    private static List<EvalRecord> runLive(List<Fixture> fixtures, EvalConfig cfg) throws Exception {
        RetrievalConfig retrievalConfig = RetrievalConfig.fromEnvironment();
        SafeLoopConfig safeLoopConfig = SafeLoopConfig.fromEnvironment();

        String uri = getConfigValue("NEO4J_URI", "neo4j.uri", NEO4J_DEFAULT_URL);
        String user = getConfigValue("NEO4J_USER", "neo4j.user", NEO4J_DEFAULT_USER);
        String password = getConfigValue("NEO4J_PASSWORD", "neo4j.password", NEO4J_DEFAULT_PASSWORD);

        Verifier verifier = new NoopVerifier();
        List<Scorer> scorers = defaultScorers();
        List<EvalRecord> records = new ArrayList<>(fixtures.size());

        try (RunContext ctx = new RunContext(uri, user, password, retrievalConfig, safeLoopConfig)) {
            for (Fixture f : fixtures) {
                Fixture effective = cfg.retrievalOnly ? withMode(f, "retrieval") : f;
                ModeRunner runner = pickRunner(effective.mode());
                RunResult res = runner.run(effective, ctx);
                EvalRecord rec = score(effective, res, verifier, scorers);
                records.add(rec);
                printFixtureLine(effective, res, rec);
                if (cfg.failFast && res.isError()) break;
            }
        }
        return records;
    }

    private static ModeRunner pickRunner(String mode) {
        return switch (mode.toLowerCase()) {
            case "retrieval" -> new RetrievalRunner();
            case "safeloop" -> new SafeLoopRunner();
            default -> throw new IllegalStateException("unsupported mode: " + mode
                    + " (runner not wired in this PR)");
        };
    }

    private static List<Scorer> defaultScorers() {
        return List.of(new RetrievalScorer(), new AnalyzerScorer(), new BuildScorer());
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

    private static void writeManifest(Path outDir, EvalConfig cfg, int runFixtures, int totalFixtures)
            throws IOException {
        Files.createDirectories(outDir);
        JsonObject root = new JsonObject();
        root.addProperty("startedAt", Instant.now().toString());
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
        JsonArray argArr = new JsonArray();
        for (String a : cfg.rawArgs) argArr.add(a);
        root.add("args", argArr);
        Files.writeString(outDir.resolve("manifest.json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(root));
    }

    private static void runReporters(Path outDir, EvalConfig cfg, List<EvalRecord> records)
            throws IOException {
        List<Reporter> reporters = new ArrayList<>();
        reporters.add(new JsonlReporter());
        reporters.add(new SummaryReporter());
        reporters.add(new JsonSummaryReporter());
        if (cfg.baselineJsonl != null) {
            reporters.add(new BaselineDiffReporter(cfg.baselineJsonl, cfg.diffEpsilon));
        }
        for (Reporter r : reporters) r.write(outDir, records);
        System.out.println();
        System.out.println("✓ Results written to " + outDir.toAbsolutePath());
    }

    private static int decideExitCode(List<EvalRecord> records, EvalConfig cfg, Path outDir) {
        int errors = 0;
        for (EvalRecord rec : records) if (rec.result().isError()) errors++;

        boolean emptyGraph = !cfg.dryRun && records.stream()
                .allMatch(r -> r.result().retrieved().isEmpty() && !r.result().isError());
        if (emptyGraph) {
            System.err.println();
            System.err.println("WARNING: every fixture returned 0 retrieved chunks.");
            System.err.println("         Neo4j graph appears empty. Run ChunkerMain on the target repo first.");
            return 2;
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
                System.err.println("✗ self-check failed: " + failed + " FAIL/ERROR metrics");
                System.err.print(sb);
                return 1;
            }
            System.out.println("✓ self-check passed");
        }

        if (errors > 0) return 1;
        if (cfg.baselineJsonl != null && regressionDetected(outDir)) return 1;
        return 0;
    }

    private static boolean regressionDetected(Path outDir) {
        Path diff = outDir.resolve(BaselineDiffReporter.JSON_FILENAME);
        if (!Files.isRegularFile(diff)) return false;
        try {
            String json = Files.readString(diff);
            JsonObject o = new com.google.gson.Gson().fromJson(json, JsonObject.class);
            return o.has("regressions") && o.getAsJsonArray("regressions").size() > 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void printFixtureLine(Fixture f, RunResult res, EvalRecord rec) {
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
    }

    // ═══════════════════════════════════════════════════════════════
    // Arg parsing
    // ═══════════════════════════════════════════════════════════════

    private static EvalConfig parseArgs(String[] args) {
        EvalConfig cfg = new EvalConfig();
        cfg.rawArgs = Arrays.copyOf(args, args.length);
        List<String> tags = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--fixtures-dir" -> cfg.fixturesDir = pathArg(args, ++i);
                case "--output-dir" -> cfg.outputDir = pathArg(args, i + 1).toAbsolutePath();
                case "--baseline" -> cfg.baselineJsonl = pathArg(args, i + 1);
                case "--diff-epsilon" -> cfg.diffEpsilon = doubleArg(args, i + 1, cfg.diffEpsilon);
                case "--id" -> cfg.idRegex = stringArg(args, i + 1);
                case "--tag" -> tags.add(stringArg(args, i + 1));
                case "--mode" -> cfg.mode = stringArg(args, i + 1);
                case "--limit" -> cfg.limit = intArg(args, i + 1, 0);
                case "--dry-run" -> { cfg.dryRun = true; continue; }
                case "--self-check" -> { cfg.selfCheck = true; continue; }
                case "--retrieval-only" -> { cfg.retrievalOnly = true; continue; }
                case "--fail-fast" -> { cfg.failFast = true; continue; }
                case "--debug" -> { cfg.debug = true; continue; }
                case "-h", "--help" -> { printHelp(); System.exit(0); }
                default -> {
                    if (args[i].startsWith("-")) {
                        System.err.println("Unknown option: " + args[i]);
                        printHelp();
                        System.exit(1);
                    }
                    continue;
                }
            }
            // Options that consumed a following value advance `i`.
            if ("--fixtures-dir".equals(args[i])
                    || "--output-dir".equals(args[i])
                    || "--baseline".equals(args[i])
                    || "--diff-epsilon".equals(args[i])
                    || "--id".equals(args[i])
                    || "--tag".equals(args[i])
                    || "--mode".equals(args[i])
                    || "--limit".equals(args[i])) {
                i++;
            }
        }
        cfg.tags = List.copyOf(tags);
        return cfg;
    }

    private static Path pathArg(String[] args, int idx) {
        if (idx >= args.length) { System.err.println("Missing path argument"); System.exit(1); }
        return Path.of(args[idx]);
    }

    private static String stringArg(String[] args, int idx) {
        if (idx >= args.length) { System.err.println("Missing string argument"); System.exit(1); }
        return args[idx];
    }

    private static int intArg(String[] args, int idx, int def) {
        if (idx >= args.length) return def;
        try { return Integer.parseInt(args[idx]); }
        catch (NumberFormatException e) { return def; }
    }

    private static double doubleArg(String[] args, int idx, double def) {
        if (idx >= args.length) return def;
        try { return Double.parseDouble(args[idx]); }
        catch (NumberFormatException e) { return def; }
    }

    private static String getConfigValue(String envKey, String sysPropKey, String defaultValue) {
        String v = System.getProperty(sysPropKey);
        if (v != null && !v.isEmpty()) return v;
        v = System.getenv(envKey);
        if (v != null && !v.isEmpty()) return v;
        return defaultValue;
    }

    private static void printHelp() {
        System.out.println("Usage: EvalMain [options]");
        System.out.println("  --fixtures-dir <path>    Override fixture directory");
        System.out.println("  --id <regex>             Include only matching fixture ids");
        System.out.println("  --tag <tag>              Include only fixtures with tag (repeatable)");
        System.out.println("  --mode <mode>            retrieval | safeloop");
        System.out.println("  --output-dir <path>      Default: eval-results/<UTC-timestamp>/");
        System.out.println("  --baseline <jsonl>       Regression diff vs prior runs.jsonl");
        System.out.println("  --diff-epsilon <d>       Default 0.05");
        System.out.println("  --dry-run                Skip Neo4j + LLM; synthesize results");
        System.out.println("  --self-check             --dry-run + assert every metric PASS/NOT_RUN");
        System.out.println("  --retrieval-only         Force fixtures to mode=retrieval");
        System.out.println("  --limit <n>              First-N fixtures after filtering");
        System.out.println("  --fail-fast              Exit nonzero on first fixture error");
        System.out.println("  --debug");
    }

    private EvalMain() {}
}
