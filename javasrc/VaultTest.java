import com.sonelli.ipaddr;
import com.sonelli.qg0;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** JVM harness: exercises the new vault implementation before it goes into the dex. */
public class VaultTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        roundTrip();
        legacyReadable();
        tamperDetected();
        wrongPassword();
        formatIsVersioned();
        malformed();
        addresses();
        System.out.println(failures == 0 ? "ALL TESTS PASSED" : failures + " FAILURE(S)");
        if (failures != 0) {
            System.exit(1);
        }
    }

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + name);
        if (!ok) {
            failures++;
        }
    }

    private static void roundTrip() throws Exception {
        String secret = "pässwörd-✓-with spaces\tand\ttabs";
        String key = "master-password";
        String blob = qg0.c(secret, key);
        check("v2 round trip", secret.equals(qg0.a(blob, key)));
    }

    private static void legacyReadable() throws Exception {
        String secret = "legacy-secret";
        String key = "master-password";
        String blob = legacyEncrypt(secret, key);
        check("legacy blob decrypts", secret.equals(qg0.a(blob, key)));
    }

    private static void tamperDetected() throws Exception {
        String key = "master-password";
        String blob = qg0.c("authenticated", key);
        String[] parts = blob.split("#");
        byte[] payload = Base64.getDecoder().decode(parts[3]);
        payload[0] ^= 0x01;
        String broken = parts[0] + "#" + parts[1] + "#" + parts[2] + "#"
                + Base64.getEncoder().encodeToString(payload);
        boolean threw = false;
        try {
            String out = qg0.a(broken, key);
            threw = out == null;
        } catch (qg0.a expected) {
            threw = true;
        }
        check("tampered ciphertext rejected", threw);
    }

    private static void wrongPassword() throws Exception {
        String blob = qg0.c("secret", "right");
        boolean rejected = false;
        try {
            rejected = qg0.a(blob, "wrong") == null;
        } catch (qg0.a expected) {
            rejected = true;
        }
        check("wrong password rejected", rejected);
    }

    private static void formatIsVersioned() {
        String blob = qg0.c("x", "y");
        check("output is v2#salt#iv#ct", blob != null && blob.startsWith("v2#") && blob.split("#").length == 4);
    }

    private static void malformed() throws Exception {
        check("null input", qg0.a(null, "k") == null);
        check("empty key", qg0.c("x", "") == null);
        check("two parts", qg0.a("aaa#bbb", "k") == null);
        check("non-base64 parts", qg0.a("a#b#c", "k") == null);
    }

    private static void addresses() {
        check("ipv4 10.0.0.1", ipaddr.isIPv4("10.0.0.1"));
        check("ipv4 255.255.255.255", ipaddr.isIPv4("255.255.255.255"));
        check("ipv4 reject 256", !ipaddr.isIPv4("256.0.0.1"));
        check("ipv4 reject name", !ipaddr.isIPv4("example.com"));
        check("ipv4 reject short", !ipaddr.isIPv4("1.2.3"));
        check("ipv6 ::1", ipaddr.isIPv6("::1"));
        check("ipv6 full", ipaddr.isIPv6("2001:0db8:85a3:0000:0000:8a2e:0370:7334"));
        check("ipv6 compressed", ipaddr.isIPv6("2001:db8::8a2e:370:7334"));
        check("ipv6 mapped", ipaddr.isIPv6("::ffff:192.168.0.1"));
        check("ipv6 link-local zone", ipaddr.isIPv6("fe80::1%wlan0"));
        check("ipv6 bracketed", ipaddr.isIPv6("[::1]"));
        check("ipv6 reject a::b::c", !ipaddr.isIPv6("1::2::3"));
        check("ipv6 reject 9 groups", !ipaddr.isIPv6("1:2:3:4:5:6:7:8:9"));
        check("ipv6 reject name", !ipaddr.isIPv6("example.com"));
    }

    /** Exactly what the old code did, so the fallback path is really exercised. */
    private static String legacyEncrypt(String plain, String password) throws Exception {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[8];
        byte[] iv = new byte[16];
        random.nextBytes(salt);
        random.nextBytes(iv);
        SecretKeySpec key = new SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                .generateSecret(new PBEKeySpec(password.toCharArray(), salt, 1000, 256))
                .getEncoded(), "AES");
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(iv));
        byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        Base64.Encoder enc = Base64.getEncoder();
        return enc.encodeToString(salt) + "#" + enc.encodeToString(iv) + "#" + enc.encodeToString(ct);
    }
}
