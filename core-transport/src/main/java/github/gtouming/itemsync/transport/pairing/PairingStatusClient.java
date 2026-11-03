package github.gtouming.itemsync.transport.pairing;

import github.gtouming.itemsync.domain.identity.DeviceId;
import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.transport.frame.Frame;
import github.gtouming.itemsync.transport.frame.FramePayloads;
import github.gtouming.itemsync.transport.frame.FrameType;
import github.gtouming.itemsync.transport.frame.PairingStatusRequest;
import github.gtouming.itemsync.transport.frame.PairingStatusResponse;
import github.gtouming.itemsync.transport.net.FrameConnection;
import github.gtouming.itemsync.transport.net.FrameHandler;
import github.gtouming.itemsync.transport.net.PeerCertificateMismatchException;
import github.gtouming.itemsync.transport.net.TlsTransportClient;
import github.gtouming.itemsync.transport.tls.TlsIdentity;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class PairingStatusClient {

    public record Result(PairingStatusResponse response, String serverFingerprint) {
    }

    private PairingStatusClient() {
    }

    public static Result query(String host, int port, TlsIdentity identity,
                               String expectedServerFingerprint, GroupId group,
                               NodeId expectedNodeId, DeviceId expectedDeviceId,
                               Duration timeout) throws IOException {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(expectedServerFingerprint, "expectedServerFingerprint");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(expectedNodeId, "expectedNodeId");
        Objects.requireNonNull(expectedDeviceId, "expectedDeviceId");
        Objects.requireNonNull(timeout, "timeout");

        long deadline = System.nanoTime() + timeout.toNanos();
        CompletableFuture<PairingStatusResponse> future = new CompletableFuture<>();
        FrameHandler handler = new FrameHandler() {
            @Override
            public void onFrame(FrameConnection connection, Frame frame) {
                if (frame.type() != FrameType.PAIRING_STATUS_ACK) {
                    future.completeExceptionally(
                            new IOException("unexpected pairing frame: " + frame.type()));
                    return;
                }
                try {
                    future.complete(FramePayloads.decodePairingStatusResponse(frame.payload()));
                } catch (RuntimeException e) {
                    future.completeExceptionally(
                            new IOException("invalid pairing status response", e));
                }
            }

            @Override
            public void onClosed(FrameConnection connection) {
                future.completeExceptionally(
                        new IOException("connection closed before pairing status"));
            }

            @Override
            public void onError(FrameConnection connection, IOException error) {
                future.completeExceptionally(error);
            }
        };

        Duration connectBudget = remaining(deadline);
        try (TlsTransportClient client = TlsTransportClient.connect(
                host, port, identity, expectedServerFingerprint, handler,
                connectBudget, connectBudget.plusSeconds(1))) {
            client.send(new Frame(FrameType.PAIRING_STATUS,
                    FramePayloads.encodePairingStatusRequest(new PairingStatusRequest(group))));
            PairingStatusResponse response = await(future, remaining(deadline));
            String actualFingerprint = client.connection().peerFingerprint();
            validate(response, group, expectedNodeId, expectedDeviceId,
                    expectedServerFingerprint, actualFingerprint);
            return new Result(response, actualFingerprint);
        } catch (PairingStatusException e) {
            throw e;
        } catch (PeerCertificateMismatchException e) {
            throw new PairingStatusException(PairingStatusException.Reason.IDENTITY_MISMATCH,
                    "pairing endpoint certificate mismatch", e);
        } catch (IOException | RuntimeException e) {
            throw new PairingStatusException(PairingStatusException.Reason.CONNECTION_FAILED,
                    "pairing endpoint connection failed", e);
        }
    }

    private static PairingStatusResponse await(CompletableFuture<PairingStatusResponse> future,
                                               Duration timeout) throws PairingStatusException {
        try {
            return future.get(Math.max(0, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new PairingStatusException(PairingStatusException.Reason.TIMEOUT,
                    "pairing status timed out", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PairingStatusException(PairingStatusException.Reason.CONNECTION_FAILED,
                    "pairing status interrupted", e);
        } catch (ExecutionException e) {
            throw new PairingStatusException(PairingStatusException.Reason.CONNECTION_FAILED,
                    "pairing status failed", e.getCause());
        }
    }

    private static void validate(PairingStatusResponse response, GroupId group,
                                 NodeId expectedNodeId, DeviceId expectedDeviceId,
                                 String expectedFingerprint, String actualFingerprint)
            throws PairingStatusException {
        if (!group.equals(response.groupId())) {
            throw identityMismatch("pairing status group mismatch");
        }
        if (!expectedNodeId.equals(response.nodeId())
                || !expectedDeviceId.equals(response.deviceId())) {
            throw identityMismatch("pairing status identity mismatch");
        }
        if (!expectedFingerprint.equalsIgnoreCase(response.fingerprint())
                || !expectedFingerprint.equalsIgnoreCase(actualFingerprint)) {
            throw identityMismatch("pairing status fingerprint mismatch");
        }
    }

    private static PairingStatusException identityMismatch(String message) {
        return new PairingStatusException(
                PairingStatusException.Reason.IDENTITY_MISMATCH, message);
    }

    private static Duration remaining(long deadlineNanos) {
        long left = deadlineNanos - System.nanoTime();
        return left <= 0 ? Duration.ZERO : Duration.ofNanos(left);
    }
}
