package com.smolnij.chunker.eval.reporter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Serializes a list of {@link EvalRecord}s to files under an output
 * directory. Reporters are independent — {@link com.smolnij.chunker.eval.EvalMain}
 * runs each one sequentially against the same output directory.
 */
public interface Reporter {
    void write(Path outDir, List<EvalRecord> records) throws IOException;
}
