package com.smolnij.chunker.eval;

import com.smolnij.chunker.config.PropertiesLoader;

import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * Configuration for {@link EvalMain}, loaded from a {@code .properties} file.
 */
public final class EvalConfig {
    public Path fixturesDir;                // null → "eval-fixtures"
    public Path outputDir;                  // null → eval-results/<UTC-ts>/
    public Path baselineJsonl;              // null → skip diff
    public double diffEpsilon = 0.05;
    public String idRegex;
    public List<String> tags = List.of();
    public String mode;                     // null → honor per-fixture
    public boolean dryRun = false;
    public boolean selfCheck = false;
    public boolean retrievalOnly = false;
    public boolean failFast = false;
    public boolean failFastOnMetric = false;
    public int limit = 0;                   // 0 = no limit
    public boolean debug = false;
    public String verifier = "noop";        // noop | compiling

    public static EvalConfig fromProperties(Properties p) {
        EvalConfig cfg = new EvalConfig();
        String fixtures = PropertiesLoader.getString(p, "eval.fixturesDir", null);
        if (fixtures != null) cfg.fixturesDir = Path.of(fixtures);

        String out = PropertiesLoader.getString(p, "eval.outputDir", null);
        if (out != null) cfg.outputDir = Path.of(out).toAbsolutePath();

        String baseline = PropertiesLoader.getString(p, "eval.baseline", null);
        if (baseline != null) cfg.baselineJsonl = Path.of(baseline);

        cfg.diffEpsilon = PropertiesLoader.getDouble(p, "eval.diffEpsilon", cfg.diffEpsilon);
        cfg.idRegex = PropertiesLoader.getString(p, "eval.idRegex", cfg.idRegex);
        cfg.tags = PropertiesLoader.getList(p, "eval.tags", cfg.tags);
        cfg.mode = PropertiesLoader.getString(p, "eval.mode", cfg.mode);
        cfg.dryRun = PropertiesLoader.getBoolean(p, "eval.dryRun", cfg.dryRun);
        cfg.selfCheck = PropertiesLoader.getBoolean(p, "eval.selfCheck", cfg.selfCheck);
        cfg.retrievalOnly = PropertiesLoader.getBoolean(p, "eval.retrievalOnly", cfg.retrievalOnly);
        cfg.failFast = PropertiesLoader.getBoolean(p, "eval.failFast", cfg.failFast);
        cfg.failFastOnMetric = PropertiesLoader.getBoolean(p, "eval.failFastOnMetric", cfg.failFastOnMetric);
        cfg.limit = PropertiesLoader.getInt(p, "eval.limit", cfg.limit);
        cfg.debug = PropertiesLoader.getBoolean(p, "eval.debug", cfg.debug);
        cfg.verifier = PropertiesLoader.getString(p, "eval.verifier", cfg.verifier);
        return cfg;
    }
}
