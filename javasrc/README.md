# javasrc — hand-maintained Java that is compiled into smali

The app ships no original source; the decode tree only has obfuscated smali. For the two
pieces where hand-written smali would be too error-prone, the class is written in Java
here, compiled, dexed and disassembled back into `smali/`.

## Pipeline

```bash
# 1. compile (pure java.* only, no android.jar needed)
javac -encoding UTF-8 --release 8 -d .tmp/jvm javasrc/com/sonelli/qg0.java \
      javasrc/com/sonelli/ipaddr.java javasrc/VaultTest.java

# 2. test on the JVM before it ever reaches the device
java -Dfile.encoding=UTF-8 -cp .tmp/jvm VaultTest

# 3. dex it (r8.jar, the d8 entry point)
java -cp tools/r8.jar com.android.tools.r8.D8 --min-api 26 --output .tmp/dex \
     .tmp/jvm/com/sonelli/qg0.class ".tmp/jvm/com/sonelli/qg0\$a.class" \
     .tmp/jvm/com/sonelli/ipaddr.class

# 4. disassemble: apktool bundles baksmali, so feed it a throwaway APK whose only
#    dex is the one above, then copy the generated smali back into the decode tree
```

Step 4 in practice: copy an already-built APK, replace `classes.dex` with the d8 output
(python `zipfile`, no `zip` binary on this box), `apktool d -r`, then copy
`smali/com/sonelli/{qg0,qg0$a,ipaddr}.smali` into `work/dec/smali/`.

## Files

- `qg0.java` — credential vault. v2 format: `v2#salt#iv#ct+tag`, PBKDF2-HmacSHA256
  210000 iterations, AES-256-GCM. Legacy CBC blobs stay readable.
- `ipaddr.java` — literal IPv4/IPv6 checks replacing `org.apache.http.conn.util.InetAddressUtils`.
- `VaultTest.java` — 23 JVM assertions; must print `ALL TESTS PASSED` before step 3.

Anything generated this way must keep the *existing* public API (class name, method
signatures, nested exception class) because other smali calls into it.
