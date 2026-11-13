package io.github.aleeibrahim.zkmatch.gallery;

import java.util.function.Consumer;

/**
 * Where enrolled templates come from. An implementation hands each stored template to the
 * consumer as it reads it, so nothing has to hold the whole raw set at once; the gallery
 * decodes each one straight into a SourceAFIS template and drops the raw bytes.
 */
public interface GallerySource {
    /** A short name for /health, e.g. "cvsecurity". */
    String name();

    /**
     * Reads every stored fingerprint template. Throws if the source cannot be read at all;
     * the gallery then keeps what it had.
     */
    void load(Consumer<StoredTemplate> sink) throws Exception;

    /**
     * One stored template.
     *
     * @param id       the person's id in the source (for CVSecurity, the person's pin)
     * @param finger   the finger number as the source stores it, or -1 if unknown
     * @param version  the template format version as the source names it, e.g. "10"
     * @param template the template, base64
     */
    record StoredTemplate(String id, int finger, String version, String template) {}
}
