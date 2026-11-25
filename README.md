# NotebookChain

Build a small proof-of-work blockchain in Java and use it as a lab notebook. Each block holds one note, such as a model’s evaluation score. You can mine a block, close the terminal, come back later and verify the whole notebook. The demo changes a score from 84% to 94% so you can see exactly what the verifier catches.

The ZIP includes the Java source, a runnable `notebook-chain.jar`, and a test suite. Everything runs locally with Java 21 or newer. The JDK supplies SHA-256 and all the file-handling code.

## 1. Run your first chain

1. Install a **JDK** (Java Development Kit) version 21 or newer for your operating system. If you already use VS Code, keep using it. The JDK gives your terminal the `java`, `javac` and `jar` commands. [JDK downloads and installation instructions](https://adoptium.net/installation/)
2. Extract the ZIP. In VS Code, choose **File → Open Folder** and select the folder containing this README and `notebook-chain.jar`. Choose **Terminal → New Terminal**. You can also use a regular terminal opened in that folder.
3. Run these commands in PowerShell, Command Prompt, macOS Terminal or a Linux shell.

```text
java -version
javac -version
java -jar notebook-chain.jar demo
```

The demo builds three blocks in memory. Look at the `hash` on block 0 and the `parent` on block 1. Those two values should match character for character. The run ends with these lines.

```text
Valid chain (3 blocks, difficulty 3).
Editing block 1 in a copy: 84% -> 94%
Edited copy rejected. Block 1: hash mismatch.
```

**Why the edit fails.** The copied block still claims the hash it earned for the original score. When the verifier hashes the bytes for the new score with the same nonce, it gets a different answer. It can detect the edit even if that block is the last one in the chain.

## 2. Create a notebook you can keep

Run these commands one at a time from the same folder. Use a new filename for each new notebook.

```text
java -jar notebook-chain.jar init lab.chain
java -jar notebook-chain.jar add lab.chain "Trained my first model"
java -jar notebook-chain.jar add lab.chain "Model A scored 84%"
java -jar notebook-chain.jar show lab.chain
java -jar notebook-chain.jar verify lab.chain
```

The last command prints `Valid chain (3 blocks, difficulty 3).` and a 64-character tip hash. Three blocks means one genesis block plus your two notes. The **tip** is the most recent block. Save its hash separately if you want to recognize this exact snapshot later. The verification and display commands both read the saved file, so you can run them again after restarting the program.

| Command after `java -jar notebook-chain.jar` | What it does |
| --- | --- |
| `demo` | Mines the same three example blocks each time and verifies the edited copy. |
| `init FILE [DIFFICULTY]` | Creates the file and mines block 0. Default difficulty is 3. Accepts 1 through 4. Refuses to overwrite an existing ledger. |
| `add FILE "note"` | Reads and verifies the existing chain, mines one new block, saves it and prints the new tip. Quote a note containing spaces. |
| `show FILE` | Verifies the chain, then prints each note, UTC timestamp, parent, hash and nonce. |
| `verify FILE [EXPECTED_TIP]` | Recalculates every block. Supplying a tip also checks that this exact snapshot matches your saved checkpoint. |
| `help` | Prints the available commands. |

Square brackets in the table mark an optional argument. Use quotes around paths containing spaces. Notes may contain Unicode and spaces and must fit in 4,096 UTF-8 bytes. Tabs, line breaks, control characters and blank notes are rejected.

## 3. Implement it from the source

If you want to recreate the project in an empty folder, work through these files in order. Keep their package declaration, `package notebook;`, and put them at the relative paths shown. After step 4 you can build and run the application using the commands in section 4.

### Step 1 — Define a block and its fingerprint

Open [Block.java](src/notebook/Block.java). A Java `record` groups related values, supplies accessors such as `block.note()`, and makes the fields final. If you know C, start by reading the declaration as a struct whose fields are set at construction.

| Field | Example from demo block 1 | Purpose |
| --- | --- | --- |
| `height` | `1` | Position in the chain, counting from zero. |
| `timeMillis` | `1000` | Milliseconds since 1 January 1970 UTC. The demo fixes times so everyone gets identical results. |
| `parent` | Starts with `000a83b7` | Complete 32-byte digest of the previous block, displayed as 64 hex digits. |
| `note` | `Model A scored 84%` | User data, encoded as UTF-8 when hashed. |
| `nonce` | `6022` | Number the miner varied to meet the difficulty rule. |
| `hash` | Starts with `000b744c` | Stored SHA-256 result that the verifier must independently reproduce. |

Implement `header` first. Two implementations only agree on a hash if they feed SHA-256 the same bytes. For example, writing two decimal numbers next to each other makes `1` followed by `23` look like `12` followed by `3`. NotebookChain fixes the width of each numeric field and writes the length of the note before the note itself.

The table gives the exact hash input in order.

| Bytes | Value |
| --- | --- |
| 4 | ASCII `NBL1`, the format identifier |
| 1 | Difficulty |
| 4 | Height, big-endian integer |
| 8 | Timestamp in milliseconds, big-endian integer |
| 32 | Parent digest, decoded from hex to raw bytes |
| 4 | Number of **UTF-8 bytes** in the note, big-endian integer |
| variable | The UTF-8 note bytes, exactly as entered |
| 8 | Nonce, big-endian integer |

Write the bytes using Java’s `ByteBuffer`. In `hashAt`, set the last eight bytes to the candidate nonce, call `MessageDigest.digest` with the full buffer and use `HexFormat` to turn the 32-byte result into lowercase hex. The stored `hash` is the output of this calculation. Feeding the output back into the input would change the calculation. [Java MessageDigest reference](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/security/MessageDigest.html) · [NIST Secure Hash Standard](https://csrc.nist.gov/pubs/fips/180-4/upd1/final)

Next implement `mine`. Start the nonce at zero. Calculate the hash, check its first few hex digits and increment the nonce until the hash starts with the required number of zeroes. Reuse the byte buffer and digest for all attempts on this block. A verifier already has the winning nonce and needs one hash calculation per block.

At difficulty 3, an acceptable hash starts with `000`. Each hex digit has 16 possible values. Modeling SHA-256 outputs as uniform, a trial succeeds with probability 1/4,096, giving an average of 4,096 attempts. Individual blocks can take many more or fewer. Try creating another notebook with `init easy.chain 1` and compare the genesis nonce with the default notebook.

### Step 2 — Link blocks and validate the chain

Open [Blockchain.java](src/notebook/Blockchain.java). The **genesis block** starts the list. Its height and timestamp are zero, its parent is 64 zeroes, and its note is `NotebookChain genesis`. Mine it using the chosen difficulty. At difficulty 3, nonce 7887 produces this digest:

```text
000a83b753ef9ac8966af6d0d9c417e3b0c98c179e4367d491445f013a6cc770
```

To implement `append`, take the current tip, use its hash as the new block’s parent, and use the list size as the next height. Choose the later of the current clock reading and the parent timestamp so a backwards clock adjustment preserves the order. Mine the block, add it to a copy of the list and construct a new verified chain. The previous snapshot is still usable.

In the `Blockchain` constructor, reject an empty list and walk the blocks from index **0** onward. Apply these checks to every block.

1. The stored height matches its position.
2. Its parent equals the previous block’s stored hash, or the zero parent at height 0.
3. Genesis has the required timestamp and note. Later timestamps are at least as large as their parent’s.
4. Recalculating SHA-256 from the saved fields and nonce produces the stored hash.
5. That hash also meets the ledger’s difficulty.

Checks 4 and 5 answer different questions. A correctly calculated hash might begin with `f` and fail the work requirement. A made-up value beginning with `000` might satisfy the prefix rule and fail recalculation. Report the position and failed rule so you can trace the problem to one block.

### Step 3 — Save and reload the ledger

Open [LedgerFile.java](src/notebook/LedgerFile.java). Keep the disk format easy to inspect in a text editor. Line 1 contains `NotebookChain/1`, a tab and the difficulty. Each following line contains six tab-separated fields in the following order.

```text
height    timeMillis    parent    nonce    hash    note
```

The spacing above shows columns. The actual separator is a single tab. `read()` parses a row into a `Block`, then passes the complete list to `Blockchain` for verification. The text file is a storage representation. Hashes are computed from the binary layout in step 1, so a Windows versus Linux line ending does not change a block’s identity.

For a write, take a lock on the sibling `.lock` file **before** reading the current snapshot. Otherwise two commands can read the same tip and overwrite one another’s additions. Write the new snapshot to a temporary file alongside the ledger, close it and atomically replace the old file. Readers can then see either complete snapshot. An idle `.lock` file is normal. The operating system releases the lock when the command exits. The parent folder must already exist and support file locks and atomic replacement.

### Step 4 — Connect the commands

Open [Main.java](src/notebook/Main.java). Start at `main` and follow `run`. For example, `add` takes the target filename and one quoted note, opens a `LedgerFile` and calls `append`. The storage layer reads and validates the old chain, the chain mines the block, and the storage layer saves the result before the CLI prints the tip. `verify` follows the read path and optionally compares the tip with an externally supplied hash. A rejected file or command prints an error and exits with status 1.

## 4. Compile and test it yourself

From the folder containing `src`, run these commands after editing a source file. They work with a full JDK in PowerShell, Command Prompt, macOS and Linux shells.

```text
javac -encoding UTF-8 -d out -sourcepath src src/notebook/Main.java
java -cp out notebook.Main demo
jar --create --file notebook-chain.jar --main-class notebook.Main -C out .
java -jar notebook-chain.jar demo
```

`javac` follows the package references under `src` and writes compiled classes to `out`. The second command runs those classes directly. The third packages them into one runnable JAR and names the entry point. Use the final command to check the rebuilt JAR.

Run the 14 automated checks with these commands.

```text
javac -encoding UTF-8 -d test-out -sourcepath src tests/notebook/ChainTests.java
java -cp test-out notebook.ChainTests
```

The final line should be `Passed 14 checks.` Read [ChainTests.java](tests/notebook/ChainTests.java) to see how the tests distinguish an edited note, an unmined hash, a wrong parent, and a valid shorter history. The suite also checks Unicode, malformed files, and simultaneous writes from two separate Java processes. The two known-answer hash values let you catch implementation errors even if your miner and verifier both make the same mistake.

## 5. Try two verification experiments

**Experiment A — Edit a saved score.** After section 2, duplicate `lab.chain` as `edited.chain` with your file manager. Open the copy in a plain-text editor, change just `84%` to `94%`, and save as UTF-8. Verify the edited copy.

```text
java -jar notebook-chain.jar verify edited.chain
```

Expect `Error: Block 2: hash mismatch.` The score is in block 2 in your notebook because you added the training note first. In the built-in demo the score is in block 1.

**Experiment B — Remove a complete block.** From `verify lab.chain`, copy the 64 characters printed after `Tip:`. Make another copy named `short.chain`. In that copy, delete the entire last block row without leaving a blank row. Verify it twice.

```text
java -jar notebook-chain.jar verify short.chain
java -jar notebook-chain.jar verify short.chain YOUR_SAVED_TIP
```

Replace `YOUR_SAVED_TIP` with the actual hash. The first command accepts the remaining two blocks because they form a valid chain. The second rejects the file because its tip differs from your saved checkpoint. An append to the original also changes its tip, so compare a checkpoint with the particular snapshot you expect.

**What verification establishes.** Anyone who controls this local file can write new notes or spend the modest work required to rebuild a different history. A checkpoint kept independently lets you recognize a previously seen tip. Someone could also mine a false score honestly. The hash commits to the text, while authorship and the truth of the score require separate evidence. This project has a single local history. It implements neither peer consensus nor a currency. Both startup verification and each file update scale linearly with the number of stored blocks, which suits a small learning notebook.

## Troubleshooting

| Symptom | What to do |
| --- | --- |
| `java` or `javac` is missing, or the JAR reports `UnsupportedClassVersionError` | Install JDK 21 or newer, open a new terminal and check both version commands. On Windows use `where.exe java` and `where.exe javac`. On macOS/Linux use `which java` and `which javac` to see what your PATH selects. |
| `Unable to access jarfile` or the compiler cannot find `src/notebook/Main.java` | Open the extracted folder that directly contains the JAR, `src` and this README. |
| `Ledger already exists` | Use `add` to keep working with that ledger, or pick a new filename for `init`. |
| `Unexpected number of arguments` | Put double quotes around the whole note and around any filename containing spaces. |
| `Missing file or folder` | Run `init` before `add`, check the filename, and create the parent directory if you specified one. |
| Error about atomic file replacement | Move the project or ledger to a regular local filesystem that supports atomic replacement. |

## How this builds on the tutorial

Kass’s Part 1 introduces text blocks, a previous-hash link, a nonce search and chain validation. The accompanying Java example mines three messages and uses Gson to display the list. Its validation loop begins at index 1. NotebookChain implements those concepts from scratch, verifies the genesis block too, specifies the exact hash input and gives you a ledger you can reopen. For a side-by-side reading, compare the tutorial’s mining loop to `Block.mine` and its validation loop to the `Blockchain` constructor. [Original article](https://medium.com/programmers-blockchain/create-simple-blockchain-java-tutorial-from-scratch-6eeed3cb03fa) · [Upstream Block.java](https://github.com/CryptoKass/NoobChain-Tutorial-Part-1/blob/2fb61b75649e9234bf430aa085bc552958796525/src/noobchain/Block.java) · [Upstream NoobChain.java](https://github.com/CryptoKass/NoobChain-Tutorial-Part-1/blob/2fb61b75649e9234bf430aa085bc552958796525/src/noobchain/NoobChain.java)
