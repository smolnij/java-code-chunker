package com.smolnij.chunker.apply.verify;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-process compilation backend using {@link javax.tools.JavaCompiler}.
 *
 * <p>The trick is the in-memory <em>overlay</em>: the verifier wraps the
 * standard {@link StandardJavaFileManager} with a forwarding manager that
 * substitutes proposed file contents for any path the user passed in
 * {@link CompilationRequest#overlay()}. javac's source-path resolution
 * (triggered for unchanged files referenced from an overlay file) flows
 * through the same hook, so cross-file breakage from a rename is caught.
 *
 * <p>Net-new files (e.g. {@code stageCreateFile} ops) are also surfaced
 * during {@code list(SOURCE_PATH, package, …)} so they participate in name
 * resolution.
 *
 * <p>v1 limitations: single-module projects only; annotation processing is
 * disabled ({@code -proc:none}) to keep latency low. {@link MavenVerifier}
 * fills both gaps via the layered dispatcher.
 */
public final class JavacVerifier implements CompilationVerifier {

    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;",
            Pattern.MULTILINE);

    private final ClasspathResolver classpathResolver;
    private final String releaseTarget;

    public JavacVerifier(ClasspathResolver classpathResolver) {
        this(classpathResolver, "17");
    }

    public JavacVerifier(ClasspathResolver classpathResolver, String releaseTarget) {
        this.classpathResolver = classpathResolver;
        this.releaseTarget = releaseTarget;
    }

    @Override
    public boolean isAvailable() {
        return ToolProvider.getSystemJavaCompiler() != null;
    }

    @Override
    public String backendName() {
        return "javac";
    }

    @Override
    public CompilationResult verify(CompilationRequest req) {
        Instant start = Instant.now();
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return CompilationResult.unavailable(
                "javac not available on this JVM (likely a JRE); install a JDK or use mode=full");
        }

        Path sourceRoot = req.repoRoot().resolve("src").resolve("main").resolve("java");
        if (!Files.exists(sourceRoot)) {
            return CompilationResult.unavailable(
                "no src/main/java under repoRoot=" + req.repoRoot()
                + "; falling back to mvn or skipping verification");
        }

        // Collect overlay sources (the units we actually want compiled).
        Map<Path, String> overlay = req.overlay() == null ? Map.of() : req.overlay();
        List<JavaFileObject> overlayJavaUnits = new ArrayList<>();
        Map<Path, InMemorySourceFile> overlayByPath = new LinkedHashMap<>();
        for (Map.Entry<Path, String> e : overlay.entrySet()) {
            Path p = e.getKey().toAbsolutePath().normalize();
            if (!p.toString().endsWith(".java")) continue;
            InMemorySourceFile f = new InMemorySourceFile(p, e.getValue());
            overlayByPath.put(p, f);
            overlayJavaUnits.add(f);
        }

        // If no Java files in the overlay, there's nothing javac can usefully
        // compile (pom-only edits land here). Treat as a pass; the layered
        // dispatcher should have routed to mvn.
        if (overlayJavaUnits.isEmpty()) {
            return new CompilationResult(true, List.of(), backendName(),
                Duration.between(start, Instant.now()),
                "overlay contained no .java files; nothing to verify in javac path");
        }

        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        StandardJavaFileManager std = compiler.getStandardFileManager(collector, Locale.ROOT, StandardCharsets.UTF_8);

        try {
            std.setLocation(StandardLocation.SOURCE_PATH, List.of(sourceRoot.toFile()));
            String classpath = classpathResolver.resolve();
            if (!classpath.isBlank()) {
                List<java.io.File> cpFiles = new ArrayList<>();
                for (String entry : classpath.split(Pattern.quote(java.io.File.pathSeparator))) {
                    if (!entry.isBlank()) cpFiles.add(new java.io.File(entry));
                }
                if (!cpFiles.isEmpty()) {
                    std.setLocation(StandardLocation.CLASS_PATH, cpFiles);
                }
            }
            // Send class output to a transient directory so javac doesn't try
            // to write into target/classes (which may be stale or read-only).
            Path classOut = Files.createTempDirectory("chunker-javac-");
            std.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classOut.toFile()));
        } catch (IOException ioe) {
            return new CompilationResult(false, List.of(globalError("file manager setup failed: " + ioe.getMessage())),
                backendName(), Duration.between(start, Instant.now()), null);
        }

        OverlayFileManager fm = new OverlayFileManager(std, overlayByPath);

        List<String> options = new ArrayList<>(Arrays.asList(
            "-proc:none",
            "-implicit:none",
            "-nowarn",
            "-Xlint:none",
            "--release", releaseTarget
        ));

        Writer compilerOut = new StringWriter();
        JavaCompiler.CompilationTask task = compiler.getTask(
            compilerOut, fm, collector, options,
            /*classes*/ null,
            overlayJavaUnits);

        boolean ok;
        try {
            ok = Boolean.TRUE.equals(task.call());
        } catch (Throwable t) {
            return new CompilationResult(false,
                List.of(globalError("javac threw: " + t.getClass().getSimpleName() + ": " + t.getMessage())),
                backendName(), Duration.between(start, Instant.now()), null);
        }

        List<CompilationDiagnostic> diags = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> d : collector.getDiagnostics()) {
            diags.add(toCompilationDiagnostic(d));
        }

        try { fm.close(); } catch (IOException ignored) {}

        return new CompilationResult(ok, diags, backendName(),
            Duration.between(start, Instant.now()), null);
    }

    private static CompilationDiagnostic globalError(String message) {
        return new CompilationDiagnostic(null, -1, -1,
            CompilationDiagnostic.Severity.ERROR, null, message);
    }

    private static CompilationDiagnostic toCompilationDiagnostic(Diagnostic<? extends JavaFileObject> d) {
        Path file = null;
        if (d.getSource() != null) {
            try {
                file = Path.of(d.getSource().toUri()).toAbsolutePath().normalize();
            } catch (Exception ignored) {
                // Synthetic source (no file URI); leave file null.
            }
        }
        CompilationDiagnostic.Severity sev = switch (d.getKind()) {
            case ERROR -> CompilationDiagnostic.Severity.ERROR;
            case WARNING, MANDATORY_WARNING -> CompilationDiagnostic.Severity.WARNING;
            default -> CompilationDiagnostic.Severity.NOTE;
        };
        return new CompilationDiagnostic(
            file,
            d.getLineNumber(),
            d.getColumnNumber(),
            sev,
            d.getCode(),
            d.getMessage(Locale.ROOT));
    }

    /** Forwarding manager that overlays in-memory file contents on the standard listing. */
    private static final class OverlayFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {
        private final Map<Path, InMemorySourceFile> overlayByPath;

        OverlayFileManager(StandardJavaFileManager delegate, Map<Path, InMemorySourceFile> overlayByPath) {
            super(delegate);
            this.overlayByPath = overlayByPath;
        }

        @Override
        public Iterable<JavaFileObject> list(Location location, String packageName,
                                             Set<JavaFileObject.Kind> kinds, boolean recurse) throws IOException {
            Iterable<JavaFileObject> base = super.list(location, packageName, kinds, recurse);
            boolean wantsSources = (location == StandardLocation.SOURCE_PATH
                    || location == StandardLocation.SOURCE_OUTPUT)
                && kinds.contains(JavaFileObject.Kind.SOURCE);
            if (!wantsSources || overlayByPath.isEmpty()) {
                return base;
            }

            List<JavaFileObject> out = new ArrayList<>();
            Set<Path> shadowed = new HashSet<>();

            for (InMemorySourceFile f : overlayByPath.values()) {
                String pkg = f.packageName();
                boolean match = recurse
                    ? (packageName.isEmpty() || pkg.equals(packageName) || pkg.startsWith(packageName + "."))
                    : pkg.equals(packageName);
                if (match) {
                    out.add(f);
                    shadowed.add(f.path());
                }
            }
            for (JavaFileObject jfo : base) {
                try {
                    Path p = Path.of(jfo.toUri()).toAbsolutePath().normalize();
                    if (!shadowed.contains(p)) out.add(jfo);
                } catch (Exception ex) {
                    out.add(jfo);
                }
            }
            return out;
        }

        @Override
        public String inferBinaryName(Location location, JavaFileObject file) {
            if (file instanceof InMemorySourceFile m) {
                return m.binaryName();
            }
            return super.inferBinaryName(location, file);
        }

        @Override
        public boolean isSameFile(javax.tools.FileObject a, javax.tools.FileObject b) {
            if (a instanceof InMemorySourceFile || b instanceof InMemorySourceFile) {
                return a == b;
            }
            return super.isSameFile(a, b);
        }
    }

    /** {@link JavaFileObject} backed by an in-memory string. */
    static final class InMemorySourceFile extends SimpleJavaFileObject {
        private final Path path;
        private final String content;
        private final String packageName;
        private final String simpleName;

        InMemorySourceFile(Path path, String content) {
            super(path.toUri(), Kind.SOURCE);
            this.path = path;
            this.content = content;
            this.packageName = extractPackage(content);
            String fileName = path.getFileName().toString();
            this.simpleName = fileName.endsWith(".java")
                ? fileName.substring(0, fileName.length() - ".java".length())
                : fileName;
        }

        Path path() { return path; }
        String packageName() { return packageName; }
        String binaryName() {
            return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return content;
        }
    }

    private static String extractPackage(String source) {
        if (source == null) return "";
        Matcher m = PACKAGE.matcher(source);
        return m.find() ? m.group(1) : "";
    }
}
