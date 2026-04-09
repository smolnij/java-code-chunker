package com.example.chunker.filter;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;

import java.util.List;
import java.util.Set;

/**
 * Detects boilerplate methods (getters, setters, toString, hashCode, equals)
 * and DTO/POJO classes that should be excluded from LLM indexing.
 *
 * <p>Detection strategies:
 * <ul>
 *   <li>Pattern-matches getter/setter by AST structure (single return / single assignment)</li>
 *   <li>Recognises Lombok annotations (@Data, @Value, @Getter, @Setter, etc.)</li>
 *   <li>Identifies pure DTO classes (only fields + boilerplate methods)</li>
 * </ul>
 */
public class BoilerplateDetector {

    private static final Set<String> BOILERPLATE_METHOD_NAMES = Set.of(
        "toString", "hashCode", "equals", "canEqual",
        "builder", "toBuilder"
    );

    private static final Set<String> LOMBOK_DATA_ANNOTATIONS = Set.of(
        "Data", "Value", "Getter", "Setter", "Builder",
        "NoArgsConstructor", "AllArgsConstructor", "RequiredArgsConstructor",
        "EqualsAndHashCode", "ToString"
    );

    /**
     * Returns true if the method is a getter, setter, or other boilerplate.
     */
    public boolean isBoilerplateMethod(MethodDeclaration method) {
        String name = method.getNameAsString();

        // Explicit boilerplate names
        if (BOILERPLATE_METHOD_NAMES.contains(name)) {
            return true;
        }

        // Getter pattern: getName() { return this.name; }  — 1 statement, returns a field
        if (name.startsWith("get") && name.length() > 3) {
            return isTrivialGetter(method);
        }

        // Setter pattern: setName(X x) { this.name = x; }  — 1 statement, assigns a field
        if (name.startsWith("set") && name.length() > 3) {
            return isTrivialSetter(method);
        }

        // is/has boolean getter
        if ((name.startsWith("is") || name.startsWith("has")) && name.length() > 2) {
            return isTrivialGetter(method);
        }

        return false;
    }

    /**
     * Returns true if the class is a pure DTO/POJO (no business logic methods).
     *
     * <p>A class is considered a DTO if:
     * <ul>
     *   <li>It has Lombok data annotations and no hand-written methods, OR</li>
     *   <li>It has Lombok data annotations and ALL hand-written methods are boilerplate, OR</li>
     *   <li>It has fields but no methods at all, OR</li>
     *   <li>ALL of its methods are getters/setters/boilerplate</li>
     * </ul>
     */
    public boolean isDtoClass(ClassOrInterfaceDeclaration classDecl) {
        // Check for Lombok DTO annotations
        for (AnnotationExpr ann : classDecl.getAnnotations()) {
            if (LOMBOK_DATA_ANNOTATIONS.contains(ann.getNameAsString())) {
                List<MethodDeclaration> methods = classDecl.getMethods();
                if (methods.isEmpty()) {
                    return true;  // Pure Lombok DTO — no hand-written methods
                }
                // If ALL methods are boilerplate, still a DTO
                return methods.stream().allMatch(this::isBoilerplateMethod);
            }
        }

        // No Lombok — check if every method is a getter/setter
        List<MethodDeclaration> methods = classDecl.getMethods();
        List<FieldDeclaration> fields = classDecl.getFields();
        if (methods.isEmpty()) {
            return !fields.isEmpty(); // has fields, no methods → DTO
        }
        return methods.stream().allMatch(this::isBoilerplateMethod);
    }

    /**
     * Trivial getter: body has exactly 1 statement which is a ReturnStmt.
     */
    private boolean isTrivialGetter(MethodDeclaration method) {
        return method.getBody()
            .filter(body -> body.getStatements().size() == 1)
            .filter(body -> body.getStatements().get(0) instanceof ReturnStmt)
            .isPresent();
    }

    /**
     * Trivial setter: body has exactly 1 statement which is an assignment expression.
     */
    private boolean isTrivialSetter(MethodDeclaration method) {
        return method.getBody()
            .filter(body -> body.getStatements().size() == 1)
            .filter(body -> {
                var stmt = body.getStatements().get(0);
                if (stmt.isExpressionStmt()) {
                    var expr = stmt.asExpressionStmt().getExpression();
                    return expr instanceof AssignExpr;
                }
                return false;
            })
            .isPresent();
    }
}

