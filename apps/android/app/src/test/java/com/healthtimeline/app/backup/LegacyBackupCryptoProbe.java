package com.healthtimeline.app.backup;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.jar.JarFile;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;

/**
 * TEST ONLY: fork with this class directory and exactly one BC 1.83 JAR.
 * Mirrors the unchanged historical HTBACKUP container, without installing a JCA provider.
 * The deterministic fixture mode is only for public synthetic test data, never application backups.
 */
public final class LegacyBackupCryptoProbe {
    public static final String PUBLIC_PASSWORD = "Synthetic-Backup-2026!";
    public static final String LEGACY_JAR_SHA256 =
        "82cf3a2af766c3bc874f6d36b9f20a8b99a8f09762dc776e8a227a45d8daaafb";

    private LegacyBackupCryptoProbe() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3 && args.length != 4) throw new IllegalArgumentException("mode input output [fixture number]");
        Path bcJar = Path.of(GCMBlockCipher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String version;
        try (JarFile jar = new JarFile(bcJar.toFile())) {
            version = jar.getManifest().getMainAttributes().getValue("Bundle-Version");
        }
        String jarHash = sha256(bcJar);
        if (!"1.83".equals(version) || !LEGACY_JAR_SHA256.equals(jarHash)) {
            throw new IllegalStateException("Legacy subprocess must load the exact BC 1.83 artifact; actual " + version + " " + jarHash);
        }
        try (InputStream input = Files.newInputStream(Path.of(args[1]));
             OutputStream output = Files.newOutputStream(Path.of(args[2]))) {
            switch (args[0]) {
                case "encrypt": encrypt(input, output, 0); break;
                case "fixture":
                    if (args.length != 4) throw new IllegalArgumentException("Fixture number required");
                    int fixture = Integer.parseInt(args[3]);
                    if (fixture < 1 || fixture > 5) throw new IllegalArgumentException("Fixture number must be 1..5");
                    encrypt(input, output, fixture);
                    break;
                case "decrypt": decrypt(input, output); break;
                default: throw new IllegalArgumentException("Unknown mode");
            }
        }
        System.out.println("BC_VERSION=" + version + ";JAR_SHA256=" + jarHash + ";MODE=" + args[0]);
    }

    private static void encrypt(InputStream input, OutputStream output, int fixture) throws Exception {
        byte[] salt = new byte[16];
        byte[] iv = new byte[12];
        if (fixture == 0) {
            SecureRandom random = new SecureRandom();
            random.nextBytes(salt);
            random.nextBytes(iv);
        } else {
            // Unique salt/key and IV per version. All passwords and plaintexts here are public.
            for (int i = 0; i < salt.length; i++) salt[i] = (byte) (fixture * 17 + i);
            for (int i = 0; i < iv.length; i++) iv[i] = (byte) (fixture * 29 + i);
        }
        DataOutputStream header = new DataOutputStream(output);
        header.write("HTBACKUP".getBytes(StandardCharsets.US_ASCII));
        header.writeInt(1);
        header.writeInt(salt.length);
        header.write(salt);
        header.writeInt(iv.length);
        header.write(iv);
        transform(input, header, salt, iv, true);
        header.flush();
    }

    private static void decrypt(InputStream input, OutputStream output) throws Exception {
        DataInputStream header = new DataInputStream(input);
        byte[] magic = new byte[8];
        header.readFully(magic);
        if (!Arrays.equals("HTBACKUP".getBytes(StandardCharsets.US_ASCII), magic) || header.readInt() != 1) {
            throw new IllegalArgumentException("Unsupported backup header");
        }
        int saltSize = header.readInt();
        if (saltSize < 8 || saltSize > 64) throw new IllegalArgumentException("Invalid salt length");
        byte[] salt = new byte[saltSize];
        header.readFully(salt);
        int ivSize = header.readInt();
        if (ivSize < 12 || ivSize > 32) throw new IllegalArgumentException("Invalid IV length");
        byte[] iv = new byte[ivSize];
        header.readFully(iv);
        transform(header, output, salt, iv, false);
    }

    private static void transform(InputStream input, OutputStream output, byte[] salt, byte[] iv, boolean encrypting)
        throws Exception {
        char[] password = PUBLIC_PASSWORD.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(password, salt, 210_000, 256);
        byte[] key;
        try { key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); Arrays.fill(password, '\0'); }
        var cipher = GCMBlockCipher.newInstance(AESEngine.newInstance());
        byte[] inputBuffer = new byte[64 * 1024];
        byte[] outputBuffer = new byte[inputBuffer.length + 32];
        try {
            cipher.init(encrypting, new AEADParameters(new KeyParameter(key), 128, iv));
            while (true) {
                int read = input.read(inputBuffer);
                if (read < 0) break;
                if (read == 0) continue;
                int count = cipher.processBytes(inputBuffer, 0, read, outputBuffer, 0);
                if (count > 0) output.write(outputBuffer, 0, count);
            }
            int count = cipher.doFinal(outputBuffer, 0);
            if (count > 0) output.write(outputBuffer, 0, count);
        } finally {
            Arrays.fill(key, (byte) 0);
            Arrays.fill(inputBuffer, (byte) 0);
            Arrays.fill(outputBuffer, (byte) 0);
            cipher.reset();
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] bytes = new byte[64 * 1024];
            int count;
            while ((count = input.read(bytes)) != -1) digest.update(bytes, 0, count);
        }
        StringBuilder text = new StringBuilder();
        for (byte value : digest.digest()) text.append(String.format("%02x", value & 255));
        return text.toString();
    }
}
