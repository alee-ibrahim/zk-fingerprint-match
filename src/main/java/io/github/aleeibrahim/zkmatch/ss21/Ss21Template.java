package io.github.aleeibrahim.zkmatch.ss21;

import java.util.List;

/**
 * The decoded part of an SS21 template.
 *
 * @param width    sensor image width in pixels, from the header
 * @param height   sensor image height in pixels, from the header
 * @param variant  the header geometry as "WxH", e.g. "280x361" (USB ZK9500) or "299x374"
 * @param minutiae the minutiae, in template order
 */
public record Ss21Template(int width, int height, String variant, List<Ss21Minutia> minutiae) {}
