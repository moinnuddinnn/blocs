package notebook;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class ChainTests {
    @FunctionalInterface
    interface Check { void run() throws Exception; }

    private static int passed;
    private static final String ROOT_HASH = "000a83b753ef9ac8966af6d0d9c417e3b0c98c179e4367d491445f013a6cc770";

    public static void main(String[] args) throws Exception {
        Blockchain chain = Blockchain.start(3).append("Model A scored 84%", 1000);
        Block root = chain.blocks().getFirst();
        Block entry = chain.tip();

        check("genesis matches independently calculated SHA-256 vector", () -> {
            equal(7887L, root.nonce());
            equal(ROOT_HASH, root.hash());
        });
        check("mining binds parent, note, time and height", () -> {
            equal(6022L, entry.nonce());
            equal("000b744c2af1f42dc8dfca0664dec779ce482658689930061996ee8bab021e4a", entry.hash());
            equal(root.hash(), entry.parent());
        });
        check("an edit to the last block is detected", () ->
                rejected("Block 1: hash mismatch", () -> new Blockchain(3, List.of(root,
                        new Block(1, 1000, root.hash(), "Model A scored 94%", entry.nonce(), entry.hash())))));
        check("genesis is checked even when it is the only block", () -> {
            Block altered = new Block(0, 0, root.parent(), root.note(), root.nonce(), Block.ROOT_PARENT);
            rejected("Block 0: hash mismatch", () -> new Blockchain(3, List.of(altered)));
            Block substitute = Block.mine(3, 0, 0, root.parent(), "Different genesis");
            rejected("unexpected genesis", () -> new Blockchain(3, List.of(substitute)));
        });
        check("correctly mined block with wrong parent is rejected", () -> {
            Block orphan = Block.mine(3, 1, 1000, Block.ROOT_PARENT, "Orphan");
            rejected("previous hash mismatch", () -> new Blockchain(3, List.of(root, orphan)));
        });
        check("valid hash without enough work is rejected", () -> {
            Block candidate = new Block(1, 1000, root.hash(), entry.note(), 0, entry.hash());
            String hash = candidate.recalculate(3);
            if (hash.startsWith("000")) throw new AssertionError("Choose an unsolved test nonce.");
            Block unsolved = new Block(1, 1000, root.hash(), entry.note(), 0, hash);
            rejected("insufficient proof of work", () -> new Blockchain(3, List.of(root, unsolved)));
        });
        check("height, clock, difficulty and empty-chain rules", () -> {
            Block wrongHeight = Block.mine(3, 3, 1200, entry.hash(), "Skipped height");
            rejected("height does not match", () -> new Blockchain(3, List.of(root, entry, wrongHeight)));
            Block early = Block.mine(3, 2, 999, entry.hash(), "Earlier");
            rejected("time moved backwards", () -> new Blockchain(3, List.of(root, entry, early)));
            equal(1000L, chain.append("Clock adjusted", 500).tip().timeMillis());
            for (int bad : List.of(0, 5)) rejected("Difficulty", () -> Blockchain.start(bad));
            rejected("Missing genesis", () -> new Blockchain(3, List.of()));
        });
        check("difficulty declaration is committed to by the block hash", () ->
                rejected("Block 0: hash mismatch", () -> new Blockchain(1, chain.blocks())));
        check("checkpoint detects removal of a complete tail", () -> {
            Blockchain prefix = new Blockchain(3, List.of(root));
            chain.checkTip(entry.hash());
            rejected("differs", () -> prefix.checkTip(entry.hash()));
        });
        check("note rules use UTF-8 byte length", () -> {
            String boundary = "é".repeat(2048);
            equal(boundary, Blockchain.start(1).append(boundary, 1).tip().note());
            for (String invalid : List.of("", "   ", "a\tb", "a\nb", "a\u2028b", "a\u2029b",
                    boundary + "x", "\ud800")) {
                rejected("", () -> chain.append(invalid, 1));
            }
        });

        Path dir = Files.createTempDirectory("notebook-tests-");
        try {
            Path path = dir.resolve("lab.chain");
            LedgerFile store = new LedgerFile(path);
            check("init and append survive reopening with Unicode intact", () -> {
                store.create(1);
                store.append("  café • परीक्षण 🧪  ");
                equal("  café • परीक्षण 🧪  ", new LedgerFile(path).read().tip().note());
            });
            check("duplicate init preserves the existing file", () -> {
                byte[] before = Files.readAllBytes(path);
                rejected("already exists", () -> store.create(1));
                if (!Arrays.equals(before, Files.readAllBytes(path))) throw new AssertionError("File changed.");
            });
            check("malformed and edited files fail without being overwritten", () -> {
                Path copy = dir.resolve("edited.chain");
                LedgerFile edited = new LedgerFile(copy);
                String original = Files.readString(path);
                String changed = original.replace("café", "cafe");
                Files.writeString(copy, changed);
                rejected("Block 1: hash mismatch", () -> edited.append("New note"));
                equal(changed, Files.readString(copy));
                Files.writeString(copy, original + "incomplete row\n");
                rejected("Line 4", edited::read);
                Files.writeString(copy, "NotebookChain/99\t1\n");
                rejected("unsupported", edited::read);
                Files.writeString(copy, "NotebookChain/1\t1\n");
                rejected("Missing genesis", edited::read);
            });
            check("independent CLI writers preserve both notes", () -> {
                Process first = startWriter(path, "First writer");
                Process second = startWriter(path, "Second writer");
                for (Process process : List.of(first, second)) {
                    if (!process.waitFor(30, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        throw new AssertionError("Writer timed out.");
                    }
                    if (process.exitValue() != 0) {
                        throw new AssertionError(new String(process.getInputStream().readAllBytes(),
                                java.nio.charset.StandardCharsets.UTF_8));
                    }
                }
                var notes = store.read().blocks().stream().map(Block::note).toList();
                equal(4, notes.size());
                if (!notes.containsAll(List.of("First writer", "Second writer"))) {
                    throw new AssertionError("A concurrent update was lost.");
                }
            });
        } finally {
            try (var files = Files.walk(dir)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
        System.out.println("Passed " + passed + " checks.");
    }

    private static Process startWriter(Path file, String note) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        return new ProcessBuilder(java.toString(), "-cp", System.getProperty("java.class.path"),
                Main.class.getName(), "add", file.toString(), note).redirectErrorStream(true).start();
    }

    private static void equal(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }

    private static void rejected(String reason, Check check) throws Exception {
        try {
            check.run();
        } catch (IllegalArgumentException expected) {
            if (expected.getMessage().contains(reason)) return;
            throw new AssertionError("Unexpected reason: " + expected.getMessage(), expected);
        }
        throw new AssertionError("Expected rejection: " + reason);
    }

    private static void check(String name, Check check) throws Exception {
        check.run();
        passed++;
        System.out.println("PASS  " + name);
    }
}
