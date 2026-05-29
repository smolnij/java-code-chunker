package com.smolnij.chunker.safeloop;

import com.smolnij.chunker.apply.ApplyResult;
import com.smolnij.chunker.apply.EditOp;
import com.smolnij.chunker.apply.PatchApplier;
import com.smolnij.chunker.apply.PatchPlan;
import com.smolnij.chunker.apply.SafetyGate;
import com.smolnij.chunker.apply.verify.CompilationRequest;
import com.smolnij.chunker.apply.verify.CompilationResult;
import com.smolnij.chunker.apply.verify.CompilationVerifier;
import com.smolnij.chunker.apply.verify.DiagnosticFormatter;
import com.smolnij.chunker.refactor.ChatService;
import com.smolnij.chunker.refactor.PromptBuilder;
import com.smolnij.chunker.refactor.RefactorConfig;
import com.smolnij.chunker.refactor.StructuredOutputSpec;
import com.smolnij.chunker.retrieval.Neo4jGraphReader;

import java.nio.file.Path;
import java.util.Map;

/**
 * {@link SafetyGate} implementation used by the Safe loop: renders the staged
 * {@link PatchPlan} as a review prompt, reuses the loop's analyzer
 * {@link ChatService}, parses the response into a {@link SafetyVerdict}, and
 * maps it to a {@link SafetyGate.Verdict}.
 *
 * <p>Applies the same {@code safetyThreshold} the main loop uses so
 * agent-driven commits cannot slip through a looser bar than the existing
 * post-hoc gate.
 *
 * <p>When a {@link CompilationVerifier} is wired, the gate pre-runs the
 * verifier against the about-to-commit plan and prepends a deterministic
 * {@code COMPILATION_STATUS} block to the analyzer prompt. The system prompt
 * tells the analyzer to treat compile errors as UNSAFE / HIGH so a plan that
 * does not compile cannot slip past the gate even if the analyzer LLM is
 * over-optimistic.
 */
final class SafeLoopApplyGate implements SafetyGate {

    private static final String SYSTEM_PROMPT = """
        You are a strict static-analysis expert reviewing a structured PatchPlan
        before it is written to disk.

        Reply ONLY with a single JSON object matching this shape (no prose outside
        the JSON, no markdown fences):
        {
          "confidence": <number in 0.0..1.0>,
          "verdict": "SAFE" | "UNSAFE",
          "risks": [
            {"description": "<what could break>",
             "severity": "HIGH" | "MEDIUM" | "LOW",
             "mitigation": "<what to do about it>"}
          ],
          "needs": ["<ClassName#methodName you need to see to be more confident>"],
          "feedback": "<general assessment of the patch>"
        }

        Rules:
        - Be conservative — default to "UNSAFE" if uncertain.
        - confidence must reflect actual certainty.
        - Use [] for empty risks/needs; use "" for empty feedback.
        - If a COMPILATION_STATUS block is present and lists any compile errors
          (anything other than "OK: no errors", "NO_STAGED_EDITS", or absent),
          return verdict "UNSAFE" with a HIGH-severity risk and confidence ≤ 0.2.
          Treat STAGING_FAILED and VERIFIER_ERROR the same way.
        """;

    private final ChatService analyzerChat;
    private final SafeLoopConfig config;
    private final CompilationVerifier verifier;
    private final CompilationRequest.Mode verifyMode;
    private final int verifyMaxErrors;
    private final Path repoRoot;
    private final Neo4jGraphReader graphReader;

    SafeLoopApplyGate(ChatService analyzerChat, SafeLoopConfig config) {
        this(analyzerChat, config, null, CompilationRequest.Mode.AUTO, 50, null, null);
    }

    SafeLoopApplyGate(ChatService analyzerChat, SafeLoopConfig config,
                      CompilationVerifier verifier,
                      CompilationRequest.Mode verifyMode,
                      int verifyMaxErrors,
                      Path repoRoot,
                      Neo4jGraphReader graphReader) {
        this.analyzerChat = analyzerChat;
        this.config = config;
        this.verifier = verifier;
        this.verifyMode = verifyMode == null ? CompilationRequest.Mode.AUTO : verifyMode;
        this.verifyMaxErrors = verifyMaxErrors;
        this.repoRoot = repoRoot;
        this.graphReader = graphReader;
    }

    @Override
    public Verdict evaluate(PatchPlan staged) {
        String compStatus = renderCompilationStatus(staged);
        String prompt = compStatus + renderPlan(staged);
        StructuredOutputSpec spec = analyzerSpec(config.getStructuredOutput());

        String response = spec != null
            ? analyzerChat.chat(SYSTEM_PROMPT, prompt, spec)
            : analyzerChat.chat(SYSTEM_PROMPT, prompt);

        SafetyVerdict verdict = SafetyVerdict.parse(response);
        boolean safe = verdict.isSafe(config.getSafetyThreshold());
        return new Verdict(safe, verdict.getConfidence(), summarize(verdict));
    }

    private String renderCompilationStatus(PatchPlan staged) {
        if (verifier == null || repoRoot == null) return "";
        try {
            ApplyResult preview = new PatchApplier(repoRoot, graphReader, true, false)
                .previewEdits(staged);
            if (!preview.isSuccess()) {
                return "COMPILATION_STATUS:\nSTAGING_FAILED: "
                    + String.join("; ", preview.getErrors())
                    + "\nNOTE: treat as UNSAFE.\n\n";
            }
            Map<Path, String> overlay = preview.getStagedContents() == null
                ? Map.of() : preview.getStagedContents();
            if (overlay.isEmpty()) {
                return "COMPILATION_STATUS:\nNO_STAGED_EDITS\n\n";
            }
            CompilationRequest req = new CompilationRequest(
                repoRoot, overlay, verifyMode, overlay.keySet(), verifyMaxErrors);
            CompilationResult res = verifier.verify(req);
            return "COMPILATION_STATUS:\n"
                + DiagnosticFormatter.format(res, repoRoot, verifyMaxErrors)
                + "\n\n";
        } catch (Exception e) {
            return "COMPILATION_STATUS:\nVERIFIER_ERROR: " + e.getMessage() + "\n\n";
        }
    }

    private static StructuredOutputSpec analyzerSpec(RefactorConfig.StructuredOutputMode mode) {
        if (mode == RefactorConfig.StructuredOutputMode.OFF) return null;
        StructuredOutputSpec.Mode wireMode = switch (mode) {
            case JSON_SCHEMA -> StructuredOutputSpec.Mode.JSON_SCHEMA;
            case JSON_OBJECT -> StructuredOutputSpec.Mode.JSON_OBJECT;
            case TOOL_CALL -> StructuredOutputSpec.Mode.TOOL_CALL;
            case OFF -> throw new IllegalStateException("unreachable");
        };
        return new StructuredOutputSpec(
            PromptBuilder.SAFETY_VERDICT_SCHEMA_NAME,
            PromptBuilder.safetyVerdictSchema(),
            wireMode);
    }

    private static String renderPlan(PatchPlan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("TASK: Evaluate the safety of this staged PatchPlan.\n\n");
        if (plan.rationale() != null && !plan.rationale().isBlank()) {
            sb.append("RATIONALE: ").append(plan.rationale()).append("\n\n");
        }
        sb.append("OPS (").append(plan.ops().size()).append("):\n\n");
        for (EditOp op : plan.ops()) {
            if (op instanceof EditOp.ReplaceMethod r) {
                sb.append("### replace_method ").append(r.fqClassName())
                    .append('#').append(r.methodName())
                    .append(" [").append(r.originalSignature()).append("]\n")
                    .append("```java\n").append(r.newCode()).append("\n```\n\n");
            } else if (op instanceof EditOp.AddMethod a) {
                sb.append("### add_method ").append(a.fqClassName()).append('\n')
                    .append("```java\n").append(a.newCode()).append("\n```\n\n");
            } else if (op instanceof EditOp.DeleteMethod d) {
                sb.append("### delete_method ").append(d.fqClassName())
                    .append('#').append(d.methodName())
                    .append(" [").append(d.originalSignature()).append("]\n\n");
            } else if (op instanceof EditOp.AddImport ai) {
                sb.append("### add_import ").append(ai.filePath())
                    .append(" — ").append(ai.importDecl()).append("\n\n");
            } else if (op instanceof EditOp.CreateFile cf) {
                sb.append("### create_file ").append(cf.relPath()).append('\n')
                    .append("```java\n").append(cf.content()).append("\n```\n\n");
            } else if (op instanceof EditOp.AddMavenDependency m) {
                sb.append("### add_maven_dependency ")
                    .append(m.groupId()).append(':').append(m.artifactId());
                if (m.version() != null && !m.version().isBlank()) sb.append(':').append(m.version());
                if (m.scope() != null && !m.scope().isBlank()) sb.append(" (scope=").append(m.scope()).append(')');
                sb.append("\n\n");
            }
        }
        return sb.toString();
    }

    private static String summarize(SafetyVerdict v) {
        StringBuilder sb = new StringBuilder();
        sb.append(v.isVerdictSafe() ? "SAFE" : "UNSAFE");
        if (!v.getRisks().isEmpty()) {
            sb.append(" | risks: ");
            for (int i = 0; i < v.getRisks().size(); i++) {
                if (i > 0) sb.append("; ");
                SafetyVerdict.Risk r = v.getRisks().get(i);
                sb.append('[').append(r.getSeverity()).append("] ").append(r.getDescription());
            }
        }
        if (v.getFeedback() != null && !v.getFeedback().isEmpty()) {
            sb.append(" | ").append(v.getFeedback());
        }
        return sb.toString();
    }
}
