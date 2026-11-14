package io.github.aleeibrahim.zkmatch.ss21;

import java.util.Base64;

/** Base64 as CVSecurity hands templates out (line breaks allowed), with decode errors as {@link Ss21Exception}s. */
public final class Base64Bytes {
    private Base64Bytes() {}

    public static byte[] decode(String base64) {
        if (base64 == null || base64.isBlank()) throw new Ss21Exception(Ss21Exception.Reason.EMPTY, "template is empty");
        try {
            return Base64.getMimeDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            throw new Ss21Exception(Ss21Exception.Reason.NOT_BASE64, "template is not valid base64");
        }
    }
}
