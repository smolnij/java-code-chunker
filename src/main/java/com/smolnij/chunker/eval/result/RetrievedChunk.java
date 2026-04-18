package com.smolnij.chunker.eval.result;

/**
 * One retrieved chunk in a fixture's top-K, with its rank and score.
 */
public record RetrievedChunk(String chunkId, double score, int rank) {}
