package io.github.andrealtb.lockscreenlyrics.online;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.DeflaterOutputStream;
import static org.junit.Assert.*;

public class OnlineLyricCryptoTest {
    // Generated with the unmodified SPlayer tripledes.ts schedule/crypt functions and zlib.
    static final String QRC = "d6a2be95d644737237d332720cff3b1c038d95e3c161da5d00fe0cc1861872246efa66362c12d08c10fa84940ea6e96de3a21c41e6e2787ff68c95284ecc331d";
    static final String TEXT = "[1000,2000]你(1100,400)好(1600,900)\n[4000,1000]Hello(4000,1000)";

    @Test public void qrcMatchesSplayerReferenceCipher() {
        assertEquals(TEXT, LyricCrypto.qrc(QRC));
    }

    @Test public void qqSignatureMatchesUpstreamRegressionVector() {
        String payload = "{\"comm\":{\"ct\":11,\"cv\":14090008,\"v\":14090008,\"chid\":\"10003505\",\"os_ver\":\"15\",\"phonetype\":\"24122RKC7C\",\"tmeAppID\":\"qqmusic\",\"nettype\":\"NETWORK_WIFI\",\"udid\":\"0\",\"OpenUDID\":\"0\",\"QIMEI36\":\"0\",\"uin\":\"0\"},\"request\":{\"module\":\"test.module\",\"method\":\"Test\",\"param\":{\"text\":\"中文 /+=\"}}}";
        assertEquals("zzc1e5fa52xvbbw2xnh0dllekgdd2hmt5j9ya7ea27b8", LyricCrypto.qqSign(payload));
    }

    @Test public void krcDecryptsHeaderXorAndZlib() throws Exception {
        assertEquals("[1000,1000]<0,500,0>你<500,500,0>好", LyricCrypto.krc(krc("[1000,1000]<0,500,0>你<500,500,0>好")));
    }

    static String krc(String text) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream zip = new DeflaterOutputStream(out)) { zip.write(text.getBytes(StandardCharsets.UTF_8)); }
        int[] key = {0x40,0x47,0x61,0x77,0x5e,0x32,0x74,0x47,0x51,0x36,0x31,0x2d,0xce,0xd2,0x6e,0x69};
        byte[] bytes = out.toByteArray(), result = new byte[bytes.length + 4];
        System.arraycopy("krc1".getBytes(StandardCharsets.US_ASCII), 0, result, 0, 4);
        for (int i = 0; i < bytes.length; i++) result[i + 4] = (byte)(bytes[i] ^ key[i % 16]);
        return Base64.getEncoder().encodeToString(result);
    }
}
