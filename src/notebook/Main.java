package notebook;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;

public final class Main {
    private static final String USAGE = """
            NotebookChain | Java proof-of-work notebook
            java -jar notebook-chain.jar demo
            java -jar notebook-chain.jar init FILE [DIFFICULTY]
            java -jar notebook-chain.jar add FILE "your note"
            java -jar notebook-chain.jar show FILE
            java -jar notebook-chain.jar verify FILE [EXPECTED_TIP]
            Difficulty is 1 to 4 (default 3). The parent folder must exist.
            """;

    public static void main(String[] args) {
        try {
            run(args);
        } catch (NoSuchFileException missing) {
            System.err.println("Missing file or folder: " + missing.getFile());
            System.exit(1);
        } catch (IllegalArgumentException | IOException failure) {
            System.err.println("Error: " + failure.getMessage());
            System.exit(1);
        }
    }

    static void run(String[] args) throws IOException {
        if (args.length == 0 || args[0].equals("help") || args[0].equals("--help")) {
            System.out.print(USAGE);
            return;
        }
        if (args[0].equals("demo")) {
            requireArgs(args, 1, 1);
            demo();
            return;
        }
        requireArgs(args, 2, 3);
        LedgerFile store = new LedgerFile(Path.of(args[1]));
        switch (args[0]) {
            case "init" -> {
                int difficulty = args.length == 3 ? Integer.parseInt(args[2]) : Blockchain.DEFAULT_DIFFICULTY;
                Blockchain chain = store.create(difficulty);
                System.out.println("Created " + args[1] + " at difficulty " + difficulty + ".");
                printTip(chain);
            }
            case "add" -> {
                requireArgs(args, 3, 3);
                Blockchain chain = store.append(args[2]);
                System.out.println("Mined block " + chain.tip().height() + " (nonce " + chain.tip().nonce() + ").");
                printTip(chain);
            }
            case "show" -> {
                requireArgs(args, 2, 2);
                Blockchain chain = store.read();
                for (Block block : chain.blocks()) printBlock(block);
            }
            case "verify" -> {
                Blockchain chain = store.read();
                if (args.length == 3) chain.checkTip(args[2]);
                System.out.println("Valid chain (" + chain.blocks().size() + " blocks, difficulty "
                        + chain.difficulty() + ").");
                printTip(chain);
            }
            default -> throw new IllegalArgumentException("Unknown command.\n" + USAGE);
        }
    }

    private static void requireArgs(String[] args, int minimum, int maximum) {
        if (args.length < minimum || args.length > maximum) {
            throw new IllegalArgumentException("Unexpected number of arguments.\n" + USAGE);
        }
    }

    private static void printTip(Blockchain chain) {
        System.out.println("Tip: " + chain.tip().hash());
    }

    private static void printBlock(Block block) {
        System.out.println("#" + block.height() + "  " + Instant.ofEpochMilli(block.timeMillis())
                + "  " + block.note());
        System.out.println("  parent " + block.parent());
        System.out.println("  hash   " + block.hash() + "  nonce " + block.nonce());
    }

    private static void demo() {
        int difficulty = Blockchain.DEFAULT_DIFFICULTY;
        Blockchain chain = Blockchain.start(difficulty)
                .append("Model A scored 84%", 1_000L)
                .append("Saved the evaluation report", 2_000L);
        for (Block block : chain.blocks()) printBlock(block);
        System.out.println("Valid chain (3 blocks, difficulty " + difficulty + ").");

        var edited = new ArrayList<>(chain.blocks());
        Block entry = edited.get(1);
        edited.set(1, new Block(entry.height(), entry.timeMillis(), entry.parent(),
                "Model A scored 94%", entry.nonce(), entry.hash()));
        System.out.println("Editing block 1 in a copy: 84% -> 94%");
        try {
            new Blockchain(difficulty, edited);
        } catch (IllegalArgumentException detected) {
            System.out.println("Edited copy rejected. " + detected.getMessage());
            return;
        }
        throw new IllegalStateException("An edited block passed validation.");
    }
}
