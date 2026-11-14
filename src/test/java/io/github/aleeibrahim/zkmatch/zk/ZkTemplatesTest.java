package io.github.aleeibrahim.zkmatch.zk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.aleeibrahim.zkmatch.Synthetic;
import io.github.aleeibrahim.zkmatch.ss21.Ss21Exception;
import io.github.aleeibrahim.zkmatch.ss21.Ss21Minutia;
import io.github.aleeibrahim.zkmatch.zk.ZkTemplates.Format;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ZkTemplatesTest {

    @Test
    void detectsEachVersionByItsMagic() {
        Random rnd = new Random(1);
        List<Ss21Minutia> f = Synthetic.finger(rnd);
        assertEquals(Format.V10, ZkTemplates.decodeAny(Synthetic.encodeBase64(f, rnd)).format());
        assertEquals(Format.V12, ZkTemplates.decodeAny(Synthetic.encode12Base64(f, rnd)).format());
        assertEquals(Format.V13, ZkTemplates.decodeAny(Synthetic.encode13Base64(f, rnd)).format());
        // 10.0 and 12.0 carry the minutiae unchanged.
        assertEquals(f, ZkTemplates.decodeAny(Synthetic.encode12Base64(f, rnd)).template().minutiae());
        assertNull(ZkTemplates.detect(new byte[40]));
        Ss21Exception e = assertThrows(Ss21Exception.class, () -> ZkTemplates.decodeAny(Base64.getEncoder().encodeToString(new byte[40])));
        assertEquals(Ss21Exception.Reason.UNKNOWN_FORMAT, e.reason());
    }

    @Test
    void namesVersionsTheWayCvSecurityDoes() {
        assertEquals(Format.V10, Format.ofVersion("10"));
        assertEquals(Format.V10, Format.ofVersion("10.0"));
        assertEquals(Format.V12, Format.ofVersion(" 12.0 "));
        assertEquals(Format.V13, Format.ofVersion("13"));
        assertNull(Format.ofVersion("39.3"));
        assertNull(Format.ofVersion(""));
        assertNull(Format.ofVersion(null));
    }

    @Test
    void parsesThePreferenceList() {
        assertEquals(List.of(Format.V10, Format.V12, Format.V13), ZkTemplates.parsePreference("10,12,13"));
        assertEquals(List.of(Format.V12, Format.V10), ZkTemplates.parsePreference(" 12.0, 10 ,12"));
        assertThrows(IllegalArgumentException.class, () -> ZkTemplates.parsePreference("10,11"));
        assertThrows(IllegalArgumentException.class, () -> ZkTemplates.parsePreference(" , "));
    }
}
