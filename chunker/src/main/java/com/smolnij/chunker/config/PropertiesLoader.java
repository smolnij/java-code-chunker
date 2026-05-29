package com.smolnij.chunker.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Loads a {@link Properties} file passed as the sole CLI argument to a main
 * class. Provides typed accessors with default values.
 *
 * <p>Each main entry point is invoked exactly as:
 * <pre>
 *   java -jar java-code-chunker.jar &lt;path-to-properties-file&gt;
 * </pre>
 *
 * <p>All configuration — Neo4j credentials, LLM endpoints, sampling
 * parameters, the user's query, etc. — is read from that file. There are no
 * other CLI arguments and no environment variables / system properties.
 */
public final class PropertiesLoader {

    private PropertiesLoader() {}

    /**
     * Validate a single CLI arg and load the named properties file. On any
     * problem (missing arg, unreadable file, bad parse) prints a usage
     * message naming the example file and calls {@code System.exit(1)}.
     */
    public static Properties loadOrExit(String[] args, String mainName, String exampleFile) {
        if (args.length != 1 || args[0] == null || args[0].isBlank()) {
            System.err.println("Usage: " + mainName + " <config.properties>");
            System.err.println();
            System.err.println("Example:");
            System.err.println("  java -cp java-code-chunker.jar " + mainName + " " + exampleFile);
            System.err.println();
            System.err.println("See " + exampleFile + " for the complete list of supported keys.");
            System.exit(1);
        }
        Path path = Path.of(args[0]);
        if (!Files.isRegularFile(path)) {
            System.err.println("ERROR: cannot read configuration file: " + path.toAbsolutePath());
            System.exit(1);
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            p.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("ERROR: failed to parse " + path.toAbsolutePath() + " — " + e.getMessage());
            System.exit(1);
        }
        return p;
    }

    public static String requireString(Properties p, String key) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("Missing required property: " + key);
        }
        return v.trim();
    }

    public static String getString(Properties p, String key, String defaultValue) {
        String v = p.getProperty(key);
        return (v == null || v.isBlank()) ? defaultValue : v.trim();
    }

    public static int getInt(Properties p, String key, int defaultValue) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Property " + key + " must be an integer: " + v);
        }
    }

    public static double getDouble(Properties p, String key, double defaultValue) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) return defaultValue;
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Property " + key + " must be a double: " + v);
        }
    }

    public static boolean getBoolean(Properties p, String key, boolean defaultValue) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) return defaultValue;
        v = v.trim().toLowerCase();
        if (v.equals("true") || v.equals("yes") || v.equals("1")) return true;
        if (v.equals("false") || v.equals("no") || v.equals("0")) return false;
        throw new IllegalArgumentException("Property " + key + " must be a boolean: " + v);
    }

    public static <E extends Enum<E>> E getEnum(Properties p, String key, Class<E> enumType, E defaultValue) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) return defaultValue;
        try {
            return Enum.valueOf(enumType, v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Property " + key + " must be one of "
                + java.util.Arrays.toString(enumType.getEnumConstants()) + ": " + v);
        }
    }

    /** Comma-separated list, trimmed and empty entries dropped. */
    public static java.util.List<String> getList(Properties p, String key, java.util.List<String> defaultValue) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) return defaultValue;
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String item : v.split(",")) {
            String t = item.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
