package github.gtouming.itemsync.transport.transfer;

import github.gtouming.itemsync.domain.error.ErrorCode;
import github.gtouming.itemsync.domain.identity.BlobHash;
import github.gtouming.itemsync.transport.frame.BlobGet;
import github.gtouming.itemsync.transport.frame.BlobResult;
import github.gtouming.itemsync.transport.frame.ChunkPayload;
import github.gtouming.itemsync.transport.frame.Frame;
import github.gtouming.itemsync.transport.frame.FramePayloads;
import github.gtouming.itemsync.transport.frame.FrameType;
import github.gtouming.itemsync.transport.net.FrameConnection;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Objects;

public final class ChunkedBlobServer {

    private ChunkedBlobServer() {
    }

    public static void serve(FrameConnection connection, BlobGet request, BlobSource source) {
        serve(connection, request, source, Integer.MAX_VALUE);
    }

    static void serve(FrameConnection connection, BlobGet request, BlobSource source, int maxChunks) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(source, "source");
        if (maxChunks <= 0) {
            throw new IllegalArgumentException("maxChunks must be positive, got " + maxChunks);
        }

        BlobHash hash = request.hash();
        if (!source.exists(hash)) {
            sendResult(connection, new BlobResult(hash, false, 0, ErrorCode.BLOB_NOT_FOUND));
            return;
        }

        try {
            long size = source.size(hash);
            long offset = request.offset();
            if (offset > size) {
                sendResult(connection, new BlobResult(hash, false, size, ErrorCode.INVALID_REQUEST));
                return;
            }

            int chunks = 0;
            try (InputStream in = source.open(hash)) {
                in.skipNBytes(offset);
                byte[] buffer = new byte[ChunkPayload.CHUNK_SIZE];
                while (chunks < maxChunks) {
                    int read = in.read(buffer);
                    if (read < 0) {
                        break;
                    }
                    if (read == 0) {
                        continue;
                    }
                    byte[] data = Arrays.copyOf(buffer, read);
                    connection.send(new Frame(FrameType.BLOB_CHUNK, ChunkPayload.encode(offset, data)));
                    offset += read;
                    chunks++;
                }
            }

            if (offset == size) {
                sendResult(connection, new BlobResult(hash, true, size, null));
            }
        } catch (IOException e) {
            sendResult(connection, new BlobResult(hash, false, 0, ErrorCode.TRANSFER_FAILED));
        }
    }

    private static void sendResult(FrameConnection connection, BlobResult result) {
        connection.send(new Frame(FrameType.BLOB_RESULT, FramePayloads.encodeBlobResult(result)));
    }
}
