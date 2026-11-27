package notebook;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

record Block(int height, long timeMillis, String parent, String note, long nonce, String hash) {
    static final String ROOT_PARENT = "0".repeat(64);
    static final String GENESIS_NOTE = "NotebookChain genesis";
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final HexFormat HEX = HexFormat.of();

    Block {
        if (height < 0 || timeMillis < 0 || nonce < 0) {
            throw new IllegalArgumentException("Height, timestamp and nonce must be nonnegative.");
        }
        requireHash(parent);
        requireHash(hash);
        requireNote(note);
    }

    static void requireHash(String value) {
        if (value == null || !HASH.matcher(value).matches()) {
            throw new IllegalArgumentException("Expected a hash of 64 lowercase hexadecimal digits.");
        }
    }

    private static void requireNote(String note) {
        if (note == null || note.isBlank() || note.codePoints().anyMatch(Block::breaksNote)) {
            throw new IllegalArgumentException("Use a nonblank note without tabs, newlines or control characters.");
        }
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(note)
                || note.getBytes(StandardCharsets.UTF_8).length > 4096) {
            throw new IllegalArgumentException("A note must be valid Unicode and fit in 4096 UTF-8 bytes.");
        }
    }

    private static boolean breaksNote(int codePoint) {
        return Character.isISOControl(codePoint) || codePoint == 0x2028 || codePoint == 0x2029;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("This Java runtime needs SHA-256 support.", unavailable);
        }
    }

    // ByteBuffer defaults to big-endian. The last eight bytes are reserved for the nonce.
    private static ByteBuffer header(int difficulty, int height, long timeMillis, String parent, String note) {
        byte[] text = note.getBytes(StandardCharsets.UTF_8);
        ByteBuffer bytes = ByteBuffer.allocate(4 + 1 + 4 + 8 + 32 + 4 + text.length + 8);
        bytes.putInt(0x4e424c31).put((byte) difficulty).putInt(height).putLong(timeMillis);
        bytes.put(HEX.parseHex(parent)).putInt(text.length).put(text);
        return bytes;
    }

    private static String hashAt(ByteBuffer header, long nonce, MessageDigest digest) {
        header.putLong(header.capacity() - Long.BYTES, nonce);
        return HEX.formatHex(digest.digest(header.array()));
    }

    String recalculate(int difficulty) {
        return hashAt(header(difficulty, height, timeMillis, parent, note), nonce, sha256());
    }

    static Block mine(int difficulty, int height, long timeMillis, String parent, String note) {
        Blockchain.requireDifficulty(difficulty);
        requireNote(note);
        requireHash(parent);
        ByteBuffer header = header(difficulty, height, timeMillis, parent, note);
        MessageDigest digest = sha256();
        String target = "0".repeat(difficulty);
        for (long candidate = 0; candidate >= 0; candidate++) {
            String fingerprint = hashAt(header, candidate, digest);
            if (fingerprint.startsWith(target)) {
                return new Block(height, timeMillis, parent, note, candidate, fingerprint);
            }
        }
        throw new IllegalStateException("Exhausted the nonce range.");
    }
}
