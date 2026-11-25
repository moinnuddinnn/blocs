package notebook;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;

/** Small local snapshots. All writers share a stable sibling lock file. */
final class LedgerFile {
    private static final String VERSION = "NotebookChain/1";
    private final Path file;

    LedgerFile(Path path) {
        file = path.toAbsolutePath().normalize();
        if (file.getFileName() == null) throw new IllegalArgumentException("Choose a ledger filename.");
    }

    Blockchain read() throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null || !header.startsWith(VERSION + "\t")) {
                throw new IllegalArgumentException("Missing or unsupported NotebookChain/1 header.");
            }
            int difficulty = Integer.parseInt(header.substring(VERSION.length() + 1));
            Blockchain.requireDifficulty(difficulty);
            var blocks = new ArrayList<Block>();
            String line;
            while ((line = reader.readLine()) != null) {
                String[] field = line.split("\t", -1);
                int lineNumber = blocks.size() + 2;
                if (field.length != 6) {
                    throw new IllegalArgumentException("Line " + lineNumber + " needs six tab-separated fields.");
                }
                try {
                    blocks.add(new Block(Integer.parseInt(field[0]), Long.parseLong(field[1]),
                            field[2], field[5], Long.parseLong(field[3]), field[4]));
                } catch (IllegalArgumentException malformed) {
                    throw new IllegalArgumentException("Line " + lineNumber + ": " + malformed.getMessage());
                }
            }
            return new Blockchain(difficulty, blocks);
        }
    }

    Blockchain create(int difficulty) throws IOException {
        return update(true, () -> Blockchain.start(difficulty));
    }

    Blockchain append(String note) throws IOException {
        return update(false, () -> read().append(note, System.currentTimeMillis()));
    }

    @FunctionalInterface
    private interface Snapshot {
        Blockchain build() throws IOException;
    }

    private Blockchain update(boolean creating, Snapshot action) throws IOException {
        Path lock = file.resolveSibling(file.getFileName() + ".lock");
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lease = channel.lock()) {
            if (!lease.isValid()) throw new IOException("Could not lock " + file);
            if (creating && Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("Ledger already exists: " + file);
            }
            Blockchain chain = action.build();
            Path temporary = Files.createTempFile(file.getParent(), ".notebook-", ".tmp");
            try {
                write(temporary, chain);
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("Choose a local folder that supports atomic file replacement.", unsupported);
            } finally {
                Files.deleteIfExists(temporary);
            }
            return chain;
        }
    }

    private static void write(Path destination, Blockchain chain) throws IOException {
        try (BufferedWriter out = Files.newBufferedWriter(destination, StandardCharsets.UTF_8)) {
            out.write(VERSION + "\t" + chain.difficulty());
            out.newLine();
            for (Block b : chain.blocks()) {
                out.write(b.height() + "\t" + b.timeMillis() + "\t" + b.parent() + "\t"
                        + b.nonce() + "\t" + b.hash() + "\t" + b.note());
                out.newLine();
            }
        }
    }
}
