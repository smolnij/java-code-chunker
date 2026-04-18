package com.smolnij.chunker.eval;

import java.nio.file.Path;

/**
 * CLI-parsed configuration for {@link EvalMain}. Purely a POJO — no
 * environment lookup — because every field is either a CLI flag or
 * derived from one.
 */
public final class EvalConfig {
    public Path fixturesDir;                // null → on-disk default, then classpath fallback
    public Path outputDir;                  // default: eval-results/<UTC-ts>/
    public Path baselineJsonl;              // null → skip diff
    public double diffEpsilon = 0.05;
    public String idRegex;
    public java.util.List<String> tags = java.util.List.of();
    public String mode;                     // null → honor per-fixture
    public boolean dryRun = false;
    public boolean selfCheck = false;
    public boolean retrievalOnly = false;
    public boolean failFast = false;
    public int limit = 0;                   // 0 = no limit
    public boolean debug = false;
    public String[] rawArgs = new String[0];
}
