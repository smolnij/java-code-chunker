package com.smolnij.chunker.apply.verify;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Dispatches a {@link CompilationRequest} to the right backend based on
 * mode + overlay content + backend availability.
 *
 * <pre>
 *   mode=FAST  → JavacVerifier; if javac unavailable → "unavailable" pass-through
 *   mode=FULL  → MavenVerifier
 *   mode=AUTO  → JavacVerifier unless overlay touches pom.xml,
 *                 javac is unavailable, or the project is multi-module
 *                 (in which case MavenVerifier)
 * </pre>
 *
 * <p>"Pass-through" on unavailable backend returns {@code success=true} with a
 * diagnostic note rather than blocking commits — this is intentional: when no
 * verifier can run we'd rather defer to the existing safety gate than refuse
 * every commit. Operators who require strict compile-check should set
 * {@code verify.mode=full} so Maven (which is far more available than a JDK)
 * is exercised.
 */
public final class LayeredCompilationVerifier implements CompilationVerifier {

    private static final Pattern MULTI_MODULE = Pattern.compile("<modules>", Pattern.CASE_INSENSITIVE);

    private final JavacVerifier javac;
    private final MavenVerifier maven;

    public LayeredCompilationVerifier(JavacVerifier javac, MavenVerifier maven) {
        this.javac = javac;
        this.maven = maven;
    }

    @Override
    public boolean isAvailable() {
        return javac.isAvailable() || maven.isAvailable();
    }

    @Override
    public String backendName() {
        return "layered";
    }

    @Override
    public CompilationResult verify(CompilationRequest req) {
        CompilationRequest.Mode mode = req.mode() == null ? CompilationRequest.Mode.AUTO : req.mode();
        switch (mode) {
            case FULL:
                return maven.verify(req);
            case FAST:
                if (javac.isAvailable()) return javac.verify(req);
                return CompilationResult.unavailable(
                    "mode=fast requested but javac unavailable; rerun with mode=full or install a JDK");
            case AUTO:
            default:
                if (req.touchesPom()) return maven.verify(req);
                if (!javac.isAvailable()) return maven.verify(req);
                if (isMultiModule(req.repoRoot())) return maven.verify(req);
                return javac.verify(req);
        }
    }

    private static boolean isMultiModule(Path repoRoot) {
        Path pom = repoRoot.resolve("pom.xml");
        if (!Files.exists(pom)) return false;
        try {
            String body = Files.readString(pom);
            return MULTI_MODULE.matcher(body).find();
        } catch (Exception ignored) {
            return false;
        }
    }
}
