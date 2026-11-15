package com.prreview.common.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Secret values come from explicitly mounted files; messages never include their contents. */
public final class SecretReader {
    private SecretReader() {}

    public static String required(String name) {
        String value = optional(name);
        if (value.isBlank())
            throw new IllegalStateException(
                    "Configure " + name + "_FILE with a nonempty secret file");
        return value;
    }

    public static String optional(String name) {
        return read(System.getenv(), name);
    }

    public static String read(Map<String, String> environment, String name) {
        String path = environment.get(name + "_FILE");
        if (path == null || path.isBlank()) return "";
        try {
            return Files.readString(Path.of(path)).strip();
        } catch (Exception error) {
            throw new IllegalStateException("Cannot read " + name + "_FILE");
        }
    }
}
