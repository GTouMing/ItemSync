package github.gtouming.itemsync.sync.engine.transport;

import github.gtouming.itemsync.domain.error.ErrorCode;
import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.model.ManifestEntry;
import github.gtouming.itemsync.transport.frame.ErrorMessage;
import github.gtouming.itemsync.transport.frame.Frame;
import github.gtouming.itemsync.transport.frame.FramePayloads;
import github.gtouming.itemsync.transport.frame.FrameType;
import github.gtouming.itemsync.transport.frame.ManifestEnd;
import github.gtouming.itemsync.transport.frame.ManifestPage;
import github.gtouming.itemsync.transport.frame.ManifestRequest;
import github.gtouming.itemsync.transport.net.FrameConnection;
import github.gtouming.itemsync.transport.net.FrameHandler;
import github.gtouming.itemsync.transport.transfer.BlobSource;
import github.gtouming.itemsync.transport.transfer.ChunkedBlobServer;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

public final class RemoteSyncServer implements FrameHandler {

    private static final int PAGE_SIZE = 500;

    private final GroupId groupId;
    private final ManifestProvider manifests;
    private final BlobSource blobs;

    public RemoteSyncServer(GroupId groupId, ManifestProvider manifests, BlobSource blobs) {
        this.groupId = Objects.requireNonNull(groupId, "groupId");
        this.manifests = Objects.requireNonNull(manifests, "manifests");
        this.blobs = Objects.requireNonNull(blobs, "blobs");
    }

    @Override
    public void onFrame(FrameConnection connection, Frame frame) {
        try {
            if (frame.type() == FrameType.MANIFEST_REQUEST) {
                serveManifest(connection, FramePayloads.decodeManifestRequest(frame.payload()));
            } else if (frame.type() == FrameType.BLOB_GET) {
                ChunkedBlobServer.serve(connection, FramePayloads.decodeBlobGet(frame.payload()), blobs);
            }
        } catch (IOException | RuntimeException e) {
            sendError(connection, e);
        }
    }

    private void serveManifest(FrameConnection connection, ManifestRequest request) throws IOException {
        if (!request.groupId().equals(groupId)) {
            connection.send(new Frame(FrameType.ERROR, FramePayloads.encodeError(
                    new ErrorMessage(ErrorCode.INVALID_REQUEST, "unknown sync group"))));
            return;
        }

        ManifestSnapshot snapshot = manifests.manifest(groupId, request.sinceSequence());
        List<ManifestEntry> entries = snapshot.entries();
        for (int start = 0; start < entries.size(); start += PAGE_SIZE) {
            int end = Math.min(start + PAGE_SIZE, entries.size());
            List<ManifestEntry> page = entries.subList(start, end);
            String cursor = end < entries.size() ? page.get(page.size() - 1).path().value() : null;
            connection.send(new Frame(FrameType.MANIFEST_PAGE,
                    FramePayloads.encodeManifestPage(new ManifestPage(page, cursor))));
        }
        connection.send(new Frame(FrameType.MANIFEST_END,
                FramePayloads.encodeManifestEnd(new ManifestEnd(entries.size(), snapshot.sequence()))));
    }

    private static void sendError(FrameConnection connection, Exception failure) {
        String message = failure.getMessage() == null
                ? failure.getClass().getSimpleName()
                : failure.getMessage();
        try {
            connection.send(new Frame(FrameType.ERROR, FramePayloads.encodeError(
                    new ErrorMessage(ErrorCode.TRANSFER_FAILED, message))));
        } catch (RuntimeException ignored) {
            // the connection is already broken
        }
    }
}
