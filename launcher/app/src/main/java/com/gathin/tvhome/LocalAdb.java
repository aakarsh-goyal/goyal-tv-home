package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/**
 * A minimal ADB client that talks to this TV's own adbd on 127.0.0.1:5555 (network debugging stays on), so the
 * launcher can run a shell command the system will not let an ordinary app run: stopping another app's process.
 * The first connection makes the TV ask "Allow USB debugging?"; with "Always allow" ticked it never asks again.
 *
 * Deliberately narrow: package-private, used only by AirPlayControl, which passes a handful of fixed commands.
 * The RSA key it authenticates with lives in this app's private storage.
 */
final class LocalAdb {
    static final class Result {
        boolean connected;
        boolean needsApproval;
        String output = "";
    }

    private static final int A_CNXN = 0x4e584e43, A_AUTH = 0x48545541, A_OPEN = 0x4e45504f,
            A_OKAY = 0x59414b4f, A_WRTE = 0x45545257, A_CLSE = 0x45534c43;
    private static final int PORT = 5555;
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    private LocalAdb() {
    }

    /** Runs one shell command; blocks, so never call it on the main thread. */
    static Result shell(Context ctx, String command, int approvalWaitMs) {
        Result r = new Result();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", PORT), 3000);
            s.setSoTimeout(10000);
            OutputStream out = s.getOutputStream();
            DataInputStream in = new DataInputStream(s.getInputStream());
            KeyPair kp = key(ctx);

            send(out, A_CNXN, 0x01000001, 256 * 1024, "host::\0".getBytes());
            Packet p = read(in);
            if (p.cmd == A_AUTH && p.a0 == 1) {
                send(out, A_AUTH, 2, 0, sign(kp.getPrivate(), p.data));
                p = read(in);
                if (p.cmd == A_AUTH) {
                    // Unknown key: offer it. The TV shows its dialog; wait for the person to approve.
                    String blob = Base64.encodeToString(publicBlob((RSAPublicKey) kp.getPublic()), Base64.NO_WRAP) + " launcher@tv\0";
                    send(out, A_AUTH, 3, 0, blob.getBytes());
                    s.setSoTimeout(Math.max(1000, approvalWaitMs));
                    try {
                        p = read(in);
                    } catch (SocketTimeoutException e) {
                        r.needsApproval = true;
                        return r;
                    }
                    s.setSoTimeout(10000);
                }
            }
            if (p.cmd != A_CNXN) return r;
            r.connected = true;

            send(out, A_OPEN, 1, 0, ("shell:" + command + "\0").getBytes());
            p = read(in);
            if (p.cmd != A_OKAY) return r;
            int remote = p.a0;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            while (true) {
                p = read(in);
                if (p.cmd == A_WRTE) {
                    buf.write(p.data, 0, p.data.length);
                    send(out, A_OKAY, 1, remote, null);
                } else if (p.cmd == A_CLSE) {
                    break;
                }
            }
            r.output = buf.toString("UTF-8");
        } catch (Exception ignored) {
        }
        return r;
    }

    // ---- protocol ---------------------------------------------------------------------------------

    private static final class Packet {
        int cmd, a0, a1;
        byte[] data;
    }

    private static void send(OutputStream out, int cmd, int a0, int a1, byte[] data) throws IOException {
        int len = data == null ? 0 : data.length;
        int sum = 0;
        for (int i = 0; i < len; i++) sum += data[i] & 0xFF;
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(a0).putInt(a1).putInt(len).putInt(sum).putInt(cmd ^ 0xFFFFFFFF);
        out.write(h.array());
        if (len > 0) out.write(data);
        out.flush();
    }

    private static Packet read(DataInputStream in) throws IOException {
        byte[] h = new byte[24];
        in.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Packet p = new Packet();
        p.cmd = b.getInt();
        p.a0 = b.getInt();
        p.a1 = b.getInt();
        int len = b.getInt();
        if (len < 0 || len > 1024 * 1024) throw new IOException("bad length");
        p.data = new byte[len];
        if (len > 0) in.readFully(p.data);
        return p;
    }

    // ---- key --------------------------------------------------------------------------------------

    private static KeyPair key(Context ctx) throws Exception {
        SharedPreferences sp = ctx.getApplicationContext().getSharedPreferences("localadb", Context.MODE_PRIVATE);
        String priv = sp.getString("priv", null), pub = sp.getString("pub", null);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (priv != null && pub != null) {
            return new KeyPair(kf.generatePublic(new X509EncodedKeySpec(Base64.decode(pub, Base64.NO_WRAP))),
                    kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(priv, Base64.NO_WRAP))));
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        sp.edit().putString("priv", Base64.encodeToString(kp.getPrivate().getEncoded(), Base64.NO_WRAP))
                .putString("pub", Base64.encodeToString(kp.getPublic().getEncoded(), Base64.NO_WRAP)).apply();
        return kp;
    }

    /** adbd signs a 20-byte token as if it were a SHA-1 digest. */
    private static byte[] sign(PrivateKey key, byte[] token) throws Exception {
        byte[] in = new byte[SHA1_PREFIX.length + token.length];
        System.arraycopy(SHA1_PREFIX, 0, in, 0, SHA1_PREFIX.length);
        System.arraycopy(token, 0, in, SHA1_PREFIX.length, token.length);
        Signature sig = Signature.getInstance("NONEwithRSA");
        sig.initSign(key);
        sig.update(in);
        return sig.sign();
    }

    /** Android's RSAPublicKey struct: 64 words, n0inv, modulus and R^2 (little-endian), exponent. */
    private static byte[] publicBlob(RSAPublicKey pub) {
        BigInteger n = pub.getModulus();
        BigInteger two32 = BigInteger.ONE.shiftLeft(32);
        long inv = BigInteger.valueOf(n.mod(two32).longValue()).modInverse(two32).longValue();
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(64);
        b.putInt((int) (-inv));
        b.put(littleEndian(n, 256));
        b.put(littleEndian(BigInteger.ONE.shiftLeft(4096).mod(n), 256));
        b.putInt(65537);
        return b.array();
    }

    private static byte[] littleEndian(BigInteger v, int len) {
        byte[] be = v.toByteArray();
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            int idx = be.length - 1 - i;
            out[i] = idx >= 0 ? be[idx] : 0;
        }
        return out;
    }
}
