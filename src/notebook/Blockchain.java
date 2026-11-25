package notebook;

import java.util.ArrayList;
import java.util.List;

/** Every instance is an immutable, fully verified snapshot. */
record Blockchain(int difficulty, List<Block> blocks) {
    static final int DEFAULT_DIFFICULTY = 3;

    Blockchain {
        requireDifficulty(difficulty);
        blocks = List.copyOf(blocks);
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("Missing genesis block.");
        }
        String expectedParent = Block.ROOT_PARENT;
        long previousTime = 0;
        String target = "0".repeat(difficulty);
        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            if (block.height() != i) throw invalid(i, "height does not match position");
            if (!block.parent().equals(expectedParent)) throw invalid(i, "previous hash mismatch");
            if (i == 0 && (block.timeMillis() != 0 || !block.note().equals(Block.GENESIS_NOTE))) {
                throw invalid(i, "unexpected genesis timestamp or note");
            }
            if (block.timeMillis() < previousTime) throw invalid(i, "time moved backwards");
            if (!block.hash().equals(block.recalculate(difficulty))) throw invalid(i, "hash mismatch");
            if (!block.hash().startsWith(target)) throw invalid(i, "insufficient proof of work");
            expectedParent = block.hash();
            previousTime = block.timeMillis();
        }
    }

    private static IllegalArgumentException invalid(int index, String reason) {
        return new IllegalArgumentException("Block " + index + ": " + reason + ".");
    }

    static void requireDifficulty(int difficulty) {
        if (difficulty < 1 || difficulty > 4) {
            throw new IllegalArgumentException("Difficulty must be an integer from 1 to 4.");
        }
    }

    static Blockchain start(int difficulty) {
        Block genesis = Block.mine(difficulty, 0, 0, Block.ROOT_PARENT, Block.GENESIS_NOTE);
        return new Blockchain(difficulty, List.of(genesis));
    }

    Block tip() {
        return blocks.getLast();
    }

    Blockchain append(String note, long nowMillis) {
        Block parent = tip();
        long time = Math.max(nowMillis, parent.timeMillis());
        Block next = Block.mine(difficulty, blocks.size(), time, parent.hash(), note);
        var updated = new ArrayList<>(blocks);
        updated.add(next);
        return new Blockchain(difficulty, updated);
    }

    void checkTip(String expectedHash) {
        Block.requireHash(expectedHash);
        if (!tip().hash().equals(expectedHash)) {
            throw new IllegalArgumentException("Tip differs from the supplied checkpoint.");
        }
    }
}
