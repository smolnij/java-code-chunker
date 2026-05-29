package com.smolnij.chunker.apply;

import com.smolnij.chunker.model.CodeChunk;
import com.smolnij.chunker.model.graph.ClassNode;
import com.smolnij.chunker.model.graph.FieldNode;
import com.smolnij.chunker.model.graph.GraphModel;
import com.smolnij.chunker.store.Neo4jGraphStore.BeforeSnapshot;
import com.smolnij.chunker.store.Neo4jGraphStore.ClassIdent;
import com.smolnij.chunker.store.Neo4jGraphStore.FieldIdent;
import com.smolnij.chunker.store.Neo4jGraphStore.MethodIdent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure correlation pass: takes the pre-prune {@link BeforeSnapshot} and the
 * just-parsed {@link GraphModel} ({@code deltaModel}), and produces a
 * mapping of old → new identifiers for symbols that were renamed (or moved,
 * or whose signature changed) inside the changed files, plus the set of
 * symbols that were deleted with no replacement.
 *
 * <p>The mapping drives
 * {@link com.smolnij.chunker.store.Neo4jGraphStore#recreateInboundEdges}
 * so callers in unchanged files keep their call-graph topology after a
 * refactor.
 *
 * <p>Inputs in priority order: explicit {@link EditOp.RenameMethod} /
 * {@link EditOp.RenameClass} / {@link EditOp.RenameField} ops seed the
 * mapping authoritatively; the heuristic fills in any symbols not covered
 * by an explicit op.
 */
final class MappingResolver {

    /**
     * @param methodRenames  oldChunkId → newChunkId
     * @param classRenames   oldClassFqn → newClassFqn
     * @param fieldRenames   oldFieldFqn → newFieldFqn
     * @param deletedMethods chunkIds of methods removed with no replacement
     * @param deletedClasses class fqNames removed with no replacement
     * @param deletedFields  field fqNames removed with no replacement
     * @param diagnostics    free-form notes useful for debugging
     */
    public record Mapping(Map<String, String> methodRenames,
                          Map<String, String> classRenames,
                          Map<String, String> fieldRenames,
                          Set<String> deletedMethods,
                          Set<String> deletedClasses,
                          Set<String> deletedFields,
                          List<String> diagnostics) {

        public static Mapping empty() {
            return new Mapping(Map.of(), Map.of(), Map.of(),
                Set.of(), Set.of(), Set.of(), List.of());
        }

        public boolean isEmpty() {
            return methodRenames.isEmpty() && classRenames.isEmpty()
                && fieldRenames.isEmpty() && deletedMethods.isEmpty()
                && deletedClasses.isEmpty() && deletedFields.isEmpty();
        }
    }

    private MappingResolver() { }

    public static Mapping resolve(BeforeSnapshot before,
                                  GraphModel after,
                                  List<EditOp> committedOps) {
        if (before == null || before.isEmpty()) return Mapping.empty();

        Map<String, String> methodRenames = new LinkedHashMap<>();
        Map<String, String> classRenames  = new LinkedHashMap<>();
        Map<String, String> fieldRenames  = new LinkedHashMap<>();
        Set<String> deletedMethods = new LinkedHashSet<>();
        Set<String> deletedClasses = new LinkedHashSet<>();
        Set<String> deletedFields  = new LinkedHashSet<>();
        List<String> diagnostics = new ArrayList<>();

        Set<String> newMethodChunkIds = new LinkedHashSet<>();
        for (CodeChunk c : after.getMethodNodes()) newMethodChunkIds.add(c.getChunkId());
        Map<String, ClassNode> newClassesByFq = new LinkedHashMap<>(after.getClassNodes());
        Map<String, FieldNode> newFieldsByFq = new LinkedHashMap<>(after.getFieldNodes());

        // ── Phase 1: explicit EditOp seeds ──
        if (committedOps != null) {
            for (EditOp op : committedOps) {
                if (op instanceof EditOp.RenameClass r) {
                    seedClassRename(r, before, newClassesByFq, classRenames, diagnostics);
                } else if (op instanceof EditOp.RenameMethod r) {
                    seedMethodRename(r, before, after, classRenames, methodRenames, diagnostics);
                } else if (op instanceof EditOp.RenameField r) {
                    seedFieldRename(r, before, after, classRenames, fieldRenames, diagnostics);
                }
            }
        }

        // ── Phase 2: heuristic class rename detection ──
        for (Map.Entry<String, ClassIdent> e : before.classesByFqn().entrySet()) {
            String oldFq = e.getKey();
            if (newClassesByFq.containsKey(oldFq)) continue;             // unchanged
            if (classRenames.containsKey(oldFq))    continue;             // covered by EditOp seed

            ClassIdent oldId = e.getValue();
            ClassNode candidate = pickClassRenameCandidate(oldId, before, newClassesByFq, classRenames);
            if (candidate != null) {
                classRenames.put(oldFq, candidate.getFqName());
                diagnostics.add("class rename (heuristic): " + oldFq + " → " + candidate.getFqName());
            } else {
                deletedClasses.add(oldFq);
            }
        }

        // ── Phase 3: heuristic method rename / signature-change / move detection ──
        // We walk old methods and either correlate to a new method (rename / sig change / moved)
        // or mark them deleted. Calls already covered by EditOp seeds are skipped.
        Set<String> claimedNewIds = new LinkedHashSet<>(methodRenames.values());
        for (Map.Entry<String, MethodIdent> e : before.methodsByChunkId().entrySet()) {
            String oldChunkId = e.getKey();
            if (newMethodChunkIds.contains(oldChunkId)) continue;        // unchanged
            if (methodRenames.containsKey(oldChunkId)) continue;          // covered

            MethodIdent oldId = e.getValue();
            String effectiveOldClass = classRenames.getOrDefault(oldId.fqClassName(), oldId.fqClassName());

            CodeChunk match = pickMethodRenameCandidate(oldId, effectiveOldClass, after,
                newMethodChunkIds, claimedNewIds);
            if (match != null) {
                methodRenames.put(oldChunkId, match.getChunkId());
                claimedNewIds.add(match.getChunkId());
                diagnostics.add("method rename/sig (heuristic): " + oldChunkId + " → " + match.getChunkId());
            } else {
                deletedMethods.add(oldChunkId);
            }
        }

        // ── Phase 4: heuristic field rename detection ──
        Set<String> claimedFieldFqns = new LinkedHashSet<>(fieldRenames.values());
        for (Map.Entry<String, FieldIdent> e : before.fieldsByFqn().entrySet()) {
            String oldFq = e.getKey();
            if (newFieldsByFq.containsKey(oldFq)) continue;
            if (fieldRenames.containsKey(oldFq)) continue;

            FieldIdent oldId = e.getValue();
            String effectiveOwner = classRenames.getOrDefault(oldId.owningClassFqn(), oldId.owningClassFqn());

            FieldNode match = pickFieldRenameCandidate(oldId, effectiveOwner, newFieldsByFq, claimedFieldFqns);
            if (match != null) {
                fieldRenames.put(oldFq, match.getFqName());
                claimedFieldFqns.add(match.getFqName());
                diagnostics.add("field rename (heuristic): " + oldFq + " → " + match.getFqName());
            } else {
                deletedFields.add(oldFq);
            }
        }

        return new Mapping(methodRenames, classRenames, fieldRenames,
            deletedMethods, deletedClasses, deletedFields, diagnostics);
    }

    // ═══════════════════════════════════════════════════════════════
    // Seed helpers (explicit EditOp signals)
    // ═══════════════════════════════════════════════════════════════

    private static void seedClassRename(EditOp.RenameClass op,
                                        BeforeSnapshot before,
                                        Map<String, ClassNode> newClassesByFq,
                                        Map<String, String> classRenames,
                                        List<String> diagnostics) {
        if (!before.classesByFqn().containsKey(op.oldFqName())) return;
        if (!newClassesByFq.containsKey(op.newFqName())) return;
        classRenames.put(op.oldFqName(), op.newFqName());
        diagnostics.add("class rename (EditOp): " + op.oldFqName() + " → " + op.newFqName());
    }

    private static void seedMethodRename(EditOp.RenameMethod op,
                                         BeforeSnapshot before,
                                         GraphModel after,
                                         Map<String, String> classRenames,
                                         Map<String, String> methodRenames,
                                         List<String> diagnostics) {
        String oldFqClass = op.fqClassName();
        String newFqClass = classRenames.getOrDefault(oldFqClass, oldFqClass);

        // Find old chunkIds that match the old (class, name) pair, possibly across overloads
        // and split parts. If paramSignature is provided, narrow on it.
        for (Map.Entry<String, MethodIdent> entry : before.methodsByChunkId().entrySet()) {
            MethodIdent id = entry.getValue();
            if (!id.fqClassName().equals(oldFqClass)) continue;
            if (!id.methodName().equals(op.oldMethodName())) continue;
            if (!op.paramSignature().isEmpty()
                && !signatureMatches(id.methodSignature(), op.paramSignature())) continue;

            String paramTypes = paramTypesFromChunkId(id.chunkId());
            String partSuffix = id.partIndex() > 0 ? "#part" + id.partIndex() : "";
            String expectedNewChunkId = newFqClass + "#" + op.newMethodName() + paramTypes + partSuffix;
            for (CodeChunk c : after.getMethodNodes()) {
                if (c.getChunkId().equals(expectedNewChunkId)) {
                    methodRenames.put(id.chunkId(), c.getChunkId());
                    diagnostics.add("method rename (EditOp): " + id.chunkId() + " → " + c.getChunkId());
                    break;
                }
            }
        }
    }

    private static void seedFieldRename(EditOp.RenameField op,
                                        BeforeSnapshot before,
                                        GraphModel after,
                                        Map<String, String> classRenames,
                                        Map<String, String> fieldRenames,
                                        List<String> diagnostics) {
        String oldOwner = op.owningClassFqn();
        String newOwner = classRenames.getOrDefault(oldOwner, oldOwner);
        String oldFq = oldOwner + "." + op.oldFieldName();
        String newFq = newOwner + "." + op.newFieldName();
        if (!before.fieldsByFqn().containsKey(oldFq)) return;
        if (!after.getFieldNodes().containsKey(newFq)) return;
        fieldRenames.put(oldFq, newFq);
        diagnostics.add("field rename (EditOp): " + oldFq + " → " + newFq);
    }

    // ═══════════════════════════════════════════════════════════════
    // Heuristic helpers
    // ═══════════════════════════════════════════════════════════════

    /**
     * Pick a class-rename candidate using these tiers, in priority order:
     * <ol>
     *   <li>Exactly one new class shares the old class's filePath and
     *       wasn't already claimed for another rename.</li>
     *   <li>Exactly one new class shares the old class's simpleName +
     *       packageName combination (cross-file rename in same package).</li>
     * </ol>
     */
    private static ClassNode pickClassRenameCandidate(ClassIdent oldId,
                                                      BeforeSnapshot before,
                                                      Map<String, ClassNode> newClassesByFq,
                                                      Map<String, String> alreadyMapped) {
        Set<String> claimed = new LinkedHashSet<>(alreadyMapped.values());

        List<ClassNode> sameFile = new ArrayList<>();
        for (ClassNode cn : newClassesByFq.values()) {
            if (claimed.contains(cn.getFqName())) continue;
            if (cn.getFilePath() != null && cn.getFilePath().equals(oldId.filePath())) {
                // Skip if a same-name class still exists in this file (means it didn't disappear)
                if (before.classesByFqn().containsKey(cn.getFqName())) continue;
                sameFile.add(cn);
            }
        }
        if (sameFile.size() == 1) return sameFile.get(0);

        List<ClassNode> sameNamePkg = new ArrayList<>();
        for (ClassNode cn : newClassesByFq.values()) {
            if (claimed.contains(cn.getFqName())) continue;
            if (oldId.simpleName().equals(cn.getSimpleName())
                && oldId.packageName().equals(cn.getPackageName())
                && !cn.getFqName().equals(oldId.fqName())) {
                sameNamePkg.add(cn);
            }
        }
        if (sameNamePkg.size() == 1) return sameNamePkg.get(0);

        return null;
    }

    /**
     * Pick a method-rename / signature-change / move candidate. Tiers:
     * <ol>
     *   <li>same fqClass + same paramTypes + different methodName → rename</li>
     *   <li>same fqClass + same methodName + different paramTypes → signature change</li>
     *   <li>same methodName + same paramTypes + different fqClass (after class
     *       rename mapping is applied) → move</li>
     * </ol>
     * Returns the first tier with exactly one candidate; ambiguity falls
     * through to the next tier or to deletion.
     */
    private static CodeChunk pickMethodRenameCandidate(MethodIdent oldId,
                                                       String effectiveOldClass,
                                                       GraphModel after,
                                                       Set<String> newMethodChunkIds,
                                                       Set<String> alreadyClaimed) {
        String oldName = oldId.methodName();
        String oldParamTypes = paramTypesFromChunkId(oldId.chunkId());
        int oldPartIndex = oldId.partIndex();

        List<CodeChunk> sameClassSameParams = new ArrayList<>();
        List<CodeChunk> sameClassSameName   = new ArrayList<>();
        List<CodeChunk> movedSameSignature  = new ArrayList<>();

        for (CodeChunk c : after.getMethodNodes()) {
            if (alreadyClaimed.contains(c.getChunkId())) continue;
            // Only consider chunks for the same partIndex to avoid pairing
            // part 0 of one method with part 1 of another.
            if (c.getPartIndex() != oldPartIndex) continue;

            String cFqClass = c.getFullyQualifiedClassName();
            String cParamTypes = paramTypesFromChunkId(c.getChunkId());
            String cName = c.getMethodName();

            boolean sameClass = effectiveOldClass.equals(cFqClass);
            if (sameClass && oldParamTypes.equals(cParamTypes) && !oldName.equals(cName)) {
                sameClassSameParams.add(c);
            } else if (sameClass && oldName.equals(cName) && !oldParamTypes.equals(cParamTypes)) {
                sameClassSameName.add(c);
            } else if (!sameClass && oldName.equals(cName) && oldParamTypes.equals(cParamTypes)) {
                movedSameSignature.add(c);
            }
        }
        if (sameClassSameParams.size() == 1) return sameClassSameParams.get(0);
        if (sameClassSameName.size()   == 1) return sameClassSameName.get(0);
        if (movedSameSignature.size()  == 1) return movedSameSignature.get(0);
        return null;
    }

    /**
     * Pick a field-rename candidate. Tier: same owning class (after class
     * rename) + same type + the only unclaimed addition.
     */
    private static FieldNode pickFieldRenameCandidate(FieldIdent oldId,
                                                      String effectiveOwner,
                                                      Map<String, FieldNode> newFieldsByFq,
                                                      Set<String> claimed) {
        List<FieldNode> candidates = new ArrayList<>();
        for (FieldNode fn : newFieldsByFq.values()) {
            if (claimed.contains(fn.getFqName())) continue;
            if (fn.getOwningClassFqn() == null) continue;
            if (!effectiveOwner.equals(fn.getOwningClassFqn())) continue;
            if (oldId.type() != null && fn.getType() != null && !oldId.type().equals(fn.getType())) continue;
            // Skip if the same name still exists (it just didn't disappear)
            if (fn.getName() != null && fn.getName().equals(oldId.name())) continue;
            candidates.add(fn);
        }
        if (candidates.size() == 1) return candidates.get(0);
        return null;
    }

    // ═══════════════════════════════════════════════════════════════
    // Signature / chunkId parsing
    // ═══════════════════════════════════════════════════════════════

    /** Extract the {@code (paramTypes)} substring from a chunkId, or empty if absent. */
    static String paramTypesFromChunkId(String chunkId) {
        if (chunkId == null) return "";
        int open = chunkId.indexOf('(');
        if (open < 0) return "";
        int close = chunkId.indexOf(')', open);
        if (close < 0) return "";
        return chunkId.substring(open, close + 1);
    }

    /**
     * Loose signature-match: extracts the param-types substring from
     * {@code methodSignature} (which is human-readable, e.g.
     * {@code "public void process(Record r)"}) and compares against
     * {@code paramSignature} (e.g. {@code "(java.lang.String, int)"}).
     * Both are normalized by stripping whitespace and parameter names.
     */
    static boolean signatureMatches(String methodSignature, String paramSignature) {
        if (methodSignature == null || paramSignature == null) return false;
        String aRaw = extractParenGroup(methodSignature);
        String bRaw = extractParenGroup(paramSignature);
        if (aRaw.isEmpty() && bRaw.isEmpty()) return false;
        return normalizeParams(aRaw).equals(normalizeParams(bRaw));
    }

    private static String extractParenGroup(String s) {
        int open = s.indexOf('(');
        int close = s.lastIndexOf(')');
        if (open < 0 || close < 0 || close < open) return "";
        return s.substring(open, close + 1);
    }

    /**
     * Normalize parameter list to comparable form: strip parameter names,
     * collapse whitespace, drop annotations and {@code final}/{@code @Foo}.
     */
    private static String normalizeParams(String parens) {
        if (parens.isEmpty()) return "";
        String inside = parens.substring(1, parens.length() - 1).trim();
        if (inside.isEmpty()) return "()";
        String[] parts = splitTopLevelCommas(inside);
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(normalizeOneParam(parts[i].trim()));
        }
        sb.append(")");
        return sb.toString();
    }

    private static String[] splitTopLevelCommas(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<' || c == '(') depth++;
            else if (c == '>' || c == ')') depth--;
            else if (c == ',' && depth == 0) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out.toArray(new String[0]);
    }

    private static String normalizeOneParam(String p) {
        String t = p.replaceAll("@\\w+(\\([^)]*\\))?", "")
                   .replaceAll("\\bfinal\\b", "")
                   .trim()
                   .replaceAll("\\s+", " ");
        // Strip the param name: keep everything up to the LAST whitespace
        // (because the type may include generics with spaces).
        int lastSpace = lastTopLevelSpace(t);
        if (lastSpace > 0) return t.substring(0, lastSpace);
        return t;
    }

    private static int lastTopLevelSpace(String t) {
        int depth = 0;
        int last = -1;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '<' || c == '(') depth++;
            else if (c == '>' || c == ')') depth--;
            else if (c == ' ' && depth == 0) last = i;
        }
        return last;
    }

}
