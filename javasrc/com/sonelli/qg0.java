package com.sonelli;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Credential vault ("AES.java" in the original app).
 *
 * Wire format, version 2:
 *     v2#base64(salt 16B)#base64(iv 12B)#base64(ciphertext + 16B GCM tag)
 * Key derivation: PBKDF2WithHmacSHA256, 210000 iterations, 256-bit key.
 * Cipher: AES-256-GCM with the string "v2" as associated data, so a blob
 * cannot be silently downgraded to the legacy format.
 *
 * Legacy format (still readable, never written):
 *     base64(salt 8B)#base64(iv 16B)#base64(ciphertext)
 * Key derivation: PBKDF2WithHmacSHA1, 1000 iterations.
 * Cipher: AES-256-CBC/PKCS5Padding (unauthenticated).
 */
public class qg0 {

    public static boolean a = false;

    private static final String V2 = "v2";
    private static final String SEP = "#";
    private static final int ITERATIONS = 210000;
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    public static class a extends Exception {
    }

    /** Decrypts either format. Returns null when the input is malformed. */
    public static String a(String str, String str2) throws a {
        if (str == null || str.length() < 1 || str2 == null || str2.length() < 1) {
            return null;
        }
        String[] parts = str.split(SEP);
        try {
            if (parts.length == 4 && V2.equals(parts[0])) {
                byte[] salt = decode(parts[1]);
                byte[] iv = decode(parts[2]);
                byte[] payload = decode(parts[3]);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, derive(salt, str2, ITERATIONS),
                        new GCMParameterSpec(TAG_BITS, iv));
                cipher.updateAAD(V2.getBytes(StandardCharsets.UTF_8));
                return new String(cipher.doFinal(payload), StandardCharsets.UTF_8);
            }
            if (parts.length != 3) {
                return null;
            }
            byte[] salt = decode(parts[0]);
            byte[] iv = decode(parts[1]);
            byte[] payload = decode(parts[2]);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, b(salt, str2), new IvParameterSpec(iv));
            return new String(cipher.doFinal(payload), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        } catch (GeneralSecurityException e) {
            throw new a();
        }
    }

    /** Legacy key derivation, kept because the legacy format still needs it. */
    public static SecretKey b(byte[] salt, String password) {
        try {
            return new SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                    .generateSecret(new PBEKeySpec(password.toCharArray(), salt, 1000, 256))
                    .getEncoded(), "AES");
        } catch (GeneralSecurityException e) {
            throw new RuntimeException(e);
        }
    }

    /** Encrypts with the current (v2) format. Returns null on failure. */
    public static String c(String str, String str2) {
        if (str == null || str.length() < 1 || str2 == null || str2.length() < 1) {
            return null;
        }
        try {
            SecureRandom random = new SecureRandom();
            byte[] salt = new byte[SALT_BYTES];
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(salt);
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, derive(salt, str2, ITERATIONS),
                    new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(V2.getBytes(StandardCharsets.UTF_8));
            byte[] payload = cipher.doFinal(str.getBytes(StandardCharsets.UTF_8));
            return V2 + SEP + encode(salt) + SEP + encode(iv) + SEP + encode(payload);
        } catch (GeneralSecurityException e) {
            return null;
        }
    }

    private static SecretKey derive(byte[] salt, String password, int iterations)
            throws GeneralSecurityException {
        return new SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(new PBEKeySpec(password.toCharArray(), salt, iterations, 256))
                .getEncoded(), "AES");
    }

    private static String encode(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] decode(String str) {
        return Base64.getDecoder().decode(str);
    }
}
