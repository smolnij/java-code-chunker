package com.smolnij.chunker.safeloop;

import com.smolnij.chunker.apply.EditOp;
import com.smolnij.chunker.apply.PatchPlan;
import com.smolnij.chunker.apply.SafetyGate;
import com.smolnij.chunker.refactor.ChatService;
import com.smolnij.chunker.refactor.PromptBuilder;
import com.smolnij.chunker.refactor.RefactorConfig;
import com.smolnij.chunker.refactor.StructuredOutputSpec;

/**
 * {@link SafetyGate} implementation used by the Safe loop: renders the staged
 * {@link PatchPlan} as a review prompt, reuses the loop's analyzer
 * {@link ChatService}, parses the response into a {@link SafetyVerdict}, and
 * maps it to a {@link SafetyGate.Verdict}.
 *
 * <p>Applies the same {@code safetyThreshold} the main loop uses so
 * agent-driven commits cannot slip through a looser bar than the existing
 * post-hoc gate.
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
        """;

    private final ChatService analyzerChat;
    private final SafeLoopConfig config;

    SafeLoopApplyGate(ChatService analyzerChat, SafeLoopConfig config) {
        this.analyzerChat = analyzerChat;
        this.config = config;
    }

    @Override
    public Verdict evaluate(PatchPlan staged) {
        String prompt = renderPlan(staged);
        StructuredOutputSpec spec = analyzerSpec(config.getStructuredOutput());

        String response = spec != null
            ? analyzerChat.chat(SYSTEM_PROMPT, prompt, spec)
            : analyzerChat.chat(SYSTEM_PROMPT, prompt);

        SafetyVerdict verdict = SafetyVerdict.parse(response);
        boolean safe = verdict.isSafe(config.getSafetyThreshold());
        return new Verdict(safe, verdict.getConfidence(), summarize(verdict));
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
