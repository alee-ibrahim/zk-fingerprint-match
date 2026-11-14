package io.github.aleeibrahim.zkmatch.zk;

import io.github.aleeibrahim.zkmatch.ss21.Base64Bytes;
import io.github.aleeibrahim.zkmatch.ss21.Ss21Decoder;
import io.github.aleeibrahim.zkmatch.ss21.Ss21Exception;
import io.github.aleeibrahim.zkmatch.ss21.Ss21Template;
import java.util.Arrays;
import java.util.List;

/** The ZK template versions this service decodes, and how to tell them apart. */
public final class ZkTemplates {
    /** A decodable template version, named as ZK software names it ("10", "12", "13"). */
    public enum Format {
        V10("10"), V12("12"), V13("13");

        private final String version;

        Format(String version) {
            this.version = version;
        }

        public String version() {
            return version;
        }

        /** The format for a version name such as "10", "10.0" or "12.0"; null for other versions. */
        public static Format ofVersion(String version) {
            String v = normalize(version);
            return Arrays.stream(values()).filter(f -> f.version.equals(v)).findFirst().orElse(null);
        }
    }

    /** A decoded template and the format it was read as. */
    public record Decoded(Format format, Ss21Template template) {}

    private ZkTemplates() {}

    /** "10.0" -> "10", "12" -> "12", blank -> "". */
    public static String normalize(String version) {
        return version == null ? "" : version.trim().replaceAll("\\.0+$", "");
    }

    /** The format from the template's magic bytes, or null when none matches. */
    public static Format detect(byte[] raw) {
        if (Ss21Decoder.Layout.SS21.matches(raw)) return Format.V10;
        if (Ss21Decoder.Layout.SR23.matches(raw)) return Format.V12;
        if (Zk13Decoder.matches(raw)) return Format.V13;
        return null;
    }

    public static Ss21Template decode(Format format, byte[] raw) {
        return switch (format) {
            case V10 -> Ss21Decoder.decode(raw, Ss21Decoder.Layout.SS21);
            case V12 -> Ss21Decoder.decode(raw, Ss21Decoder.Layout.SR23);
            case V13 -> Zk13Decoder.decode(raw);
        };
    }

    /** A base64 template of any supported version, recognised by its magic bytes (for probes). */
    public static Decoded decodeAny(String base64) {
        byte[] raw = Base64Bytes.decode(base64);
        try {
            Format f = detect(raw);
            if (f == null) {
                throw new Ss21Exception(Ss21Exception.Reason.UNKNOWN_FORMAT,
                        "not a supported template: no SS21 (ZK 10.0), SR23 (ZK 12.0) or ZK 13.0 magic");
            }
            return new Decoded(f, decode(f, raw));
        } finally {
            Arrays.fill(raw, (byte) 0);
        }
    }

    /** A stored base64 template of the given version, as the source labelled it. */
    public static Decoded decodeStored(Format format, String base64) {
        byte[] raw = Base64Bytes.decode(base64);
        try {
            return new Decoded(format, decode(format, raw));
        } finally {
            Arrays.fill(raw, (byte) 0);
        }
    }

    /** Parses a preference list such as "10,12,13". */
    public static List<Format> parsePreference(String list) {
        List<Format> out = new java.util.ArrayList<>();
        for (String part : list.split(",")) {
            if (part.isBlank()) continue;
            Format f = Format.ofVersion(part);
            if (f == null) throw new IllegalArgumentException("unsupported template version " + part.trim() + "; use 10, 12 and/or 13");
            if (!out.contains(f)) out.add(f);
        }
        if (out.isEmpty()) throw new IllegalArgumentException("at least one template version is needed");
        return List.copyOf(out);
    }
}
