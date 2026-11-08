import com.android.apksig.ApkSigner;
import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal v1+v2+v3 signer built on apksig.
 * usage: Sign <keystore> <alias> <storepass> <keypass> <in.apk> <out.apk>
 */
public final class Sign {
    public static void main(String[] args) throws Exception {
        String ksPath = args[0];
        String alias = args[1];
        char[] storePass = args[2].toCharArray();
        char[] keyPass = args[3].toCharArray();
        File in = new File(args[4]);
        File out = new File(args[5]);

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream fis = new FileInputStream(ksPath)) {
            ks.load(fis, storePass);
        }
        PrivateKey key = (PrivateKey) ks.getKey(alias, keyPass);
        X509Certificate cert = (X509Certificate) ks.getCertificate(alias);

        List<ApkSigner.SignerConfig> signers = new ArrayList<>();
        signers.add(new ApkSigner.SignerConfig.Builder(
                alias, key, java.util.Collections.singletonList(cert)).build());

        new ApkSigner.Builder(signers)
                .setInputApk(in)
                .setOutputApk(out)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build()
                .sign();
        System.out.println("signed -> " + out.getAbsolutePath());
    }
}
