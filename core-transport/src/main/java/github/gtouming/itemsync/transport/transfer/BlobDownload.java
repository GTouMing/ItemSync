package github.gtouming.itemsync.transport.transfer;

import github.gtouming.itemsync.domain.identity.BlobHash;
import github.gtouming.itemsync.transport.frame.BlobGet;
import github.gtouming.itemsync.transport.frame.BlobResult;
import github.gtouming.itemsync.transport.frame.Chunk;
import github.gtouming.itemsync.transport.frame.ChunkPayload;
import github.gtouming.itemsync.transport.frame.Frame;
import github.gtouming.itemsync.transport.frame.FramePayloads;
import github.gtouming.itemsync.transport.frame.ProtocolException;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

public final class BlobDownload implements AutoCloseable {

    private static final int BUFFER_SIZE = 8192;

    private final BlobHash expectedHash;
    private final RandomAccessFile file;
    private long offset;
    private boolean completed;

    public BlobDownload(Path target, BlobHash expectedHash) throws IOException {
        Objects.requireNonNull(target, "target");
        this.expectedHash = Objects.requireNonNull(expectedHash, "expectedHash");
        this.file = new RandomAccessFile(target.toFile(), "rw");
        this.offset = file.length();
    }

    public BlobGet request() {
        return new BlobGet(expectedHash, offset);
    }

    public long offset() {
        return offset;
    }

    public boolean isCompleted() {
        return completed;
    }

    public void apply(Frame frame) throws IOException {
        switch (frame.type()) {
            case BLOB_CHUNK -> applyChunk(frame);
            case BLOB_RESULT -> applyResult(frame);
            default -> {
                // frames unrelated to the download are ignored
            }
        }
    }

    public BlobHash actualHash() throws IOException {
        file.seek(0);
        MessageDigest digest = sha256();
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = file.read(buffer)) >= 0) {
            if (read > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return BlobHash.ofSha256(digest.digest());
    }

    public boolean verify() throws IOException {
        return actualHash().equals(expectedHash);
    }

    @Override
    public void close() throws IOException {
        file.close();
    }

    private void applyChunk(Frame frame) throws IOException {
        Chunk chunk = ChunkPayload.decode(frame.payload());
        if (chunk.offset() != offset) {
            throw new ProtocolException(
                    "unexpected chunk offset: " + chunk.offset() + ", expected " + offset);
        }
        file.seek(offset);
        file.write(chunk.data());
        offset += chunk.data().length;
    }

    private void applyResult(Frame frame) throws IOException {
        BlobResult result = FramePayloads.decodeBlobResult(frame.payload());
        if (!result.ok()) {
            throw new IOException("blob transfer failed: " + result.errorCode());
        }
        if (!result.hash().equals(expectedHash)) {
            throw new ProtocolException("result carries an unexpected blob hash");
        }
        if (offset != result.size()) {
            throw new ProtocolException("blob transfer incomplete: " + offset + "/" + result.size());
        }
        file.setLength(offset);
        completed = true;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
