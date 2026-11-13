package io.github.aleeibrahim.zkmatch.afis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.machinezoo.sourceafis.FingerprintCompatibility;
import com.machinezoo.sourceafis.FingerprintImage;
import com.machinezoo.sourceafis.FingerprintImageOptions;
import com.machinezoo.sourceafis.FingerprintTemplate;
import io.github.aleeibrahim.zkmatch.ss21.Ss21Minutia;
import io.github.aleeibrahim.zkmatch.ss21.Ss21Template;

/**
 * Turns decoded SS21 minutiae and live images into SourceAFIS templates.
 *
 * <p>SourceAFIS has no public constructor for a template from minutiae, so this writes its
 * serialized template format (CBOR: width, height, positionsX/Y, directions in radians,
 * types) and lets the library parse and validate it. That format is tied to the library
 * version, which is why the version string comes from the library itself; the tests catch a
 * change when SourceAFIS is upgraded.
 */
public final class AfisTemplates {
    private static final ObjectMapper CBOR = new ObjectMapper(new CBORFactory());
    private static final String VERSION = FingerprintCompatibility.version() + "-java";

    static {
        // ImageIO caches streams in temporary files by default. Keep images in memory only: nothing
        // about a live fingerprint should touch the disk, and the container's root is read-only.
        javax.imageio.ImageIO.setUseCache(false);
    }

    private AfisTemplates() {}

    /**
     * A SourceAFIS template from SS21 minutiae. Every minutia is typed as an ending: the SS21
     * flags byte is not understood well enough to tell endings from bifurcations, and matching
     * works without it.
     */
    public static FingerprintTemplate fromSs21(Ss21Template t) {
        int width = t.width(), height = t.height();
        for (Ss21Minutia m : t.minutiae()) {
            width = Math.max(width, m.x() + 1);
            height = Math.max(height, m.y() + 1);
        }
        ObjectNode o = CBOR.createObjectNode();
        o.put("version", VERSION);
        o.put("width", width);
        o.put("height", height);
        ArrayNode xs = o.putArray("positionsX"), ys = o.putArray("positionsY"), ds = o.putArray("directions");
        StringBuilder types = new StringBuilder(t.minutiae().size());
        for (Ss21Minutia m : t.minutiae()) {
            xs.add(m.x());
            ys.add(m.y());
            ds.add((float) m.radians());
            types.append('E');
        }
        o.put("types", types.toString());
        try {
            return new FingerprintTemplate(CBOR.writeValueAsBytes(o));
        } catch (Exception e) {
            throw new IllegalArgumentException("SourceAFIS rejected the converted template: " + e.getClass().getSimpleName(), e);
        }
    }

    /**
     * A SourceAFIS template extracted from an image (PNG, JPEG, BMP and the other formats
     * SourceAFIS decodes). The image bytes are not kept.
     */
    public static FingerprintTemplate fromImage(byte[] image, double dpi) {
        FingerprintImage img;
        try {
            img = new FingerprintImage(image, new FingerprintImageOptions().dpi(dpi));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("the image could not be read as PNG, JPEG or BMP");
        }
        return new FingerprintTemplate(img);
    }
}
