package io.github.aleeibrahim.zkmatch.ss21;

/**
 * One minutia record from an SS21 template.
 *
 * @param x     pixels from the left
 * @param y     pixels from the top
 * @param angle direction in 1/256ths of a full turn (multiply by 360/256 for degrees)
 * @param flags the record's last byte; its meaning is not known and it is not used for matching
 */
public record Ss21Minutia(int x, int y, int angle, int flags) {
    /** Direction in radians, 0..2π. */
    public double radians() {
        return angle * 2 * Math.PI / 256;
    }
}
