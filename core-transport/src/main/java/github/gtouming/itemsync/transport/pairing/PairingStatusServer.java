package github.gtouming.itemsync.transport.pairing;

import github.gtouming.itemsync.domain.compat.Compat;
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

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/**
 * Answers {@code PAIRING_STATUS} requests for every group this node enables on its one
 * shared pairing endpoint (ADR-0036): the request names the group and the answer reports
 * that group's view of the caller. Requests for a group this node does not serve are not
 * answered at all, because any response would state another group's trust.
 */
public final class PairingStatusServer implements FrameHandler {

    private final Map<GroupId, PairingTrustProvider> trustByGroup;
    private final NodeId nodeId;
    private final DeviceId deviceId;
    private final String fingerprint;

    /**
     * Serves a single group; kept for the single-group runtime and its callers.
     */
    public PairingStatusServer(GroupId groupId, NodeId nodeId, DeviceId deviceId,
                               String fingerprint, PairingTrustProvider trust) {
        this(Compat.map(Objects.requireNonNull(groupId, "groupId"),
                Objects.requireNonNull(trust, "trust")), nodeId, deviceId, fingerprint);
    }

    public PairingStatusServer(Map<GroupId, PairingTrustProvider> trustByGroup, NodeId nodeId,
                               DeviceId deviceId, String fingerprint) {
        Objects.requireNonNull(trustByGroup, "trustByGroup");
        if (trustByGroup.isEmpty()) {
            throw new IllegalArgumentException(
                    "a pairing status server must serve at least one group");
        }
        this.trustByGroup = Compat.copyMap(trustByGroup);
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.deviceId = Objects.requireNonNull(deviceId, "deviceId");
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
    }

    @Override
    public void onFrame(FrameConnection connection, Frame frame) {
        if (frame.type() != FrameType.PAIRING_STATUS) {
            connection.close();
            return;
        }
        try {
            PairingStatusRequest request = FramePayloads.decodePairingStatusRequest(frame.payload());
            PairingTrustProvider trust = trustByGroup.get(request.groupId());
            if (trust == null) {
                return;
            }
            boolean trusts = trust.trusts(request.groupId(), connection.peerFingerprint());
            PairingStatusResponse response = new PairingStatusResponse(request.groupId(), nodeId,
                    deviceId, fingerprint, trusts);
            connection.send(new Frame(FrameType.PAIRING_STATUS_ACK,
                    FramePayloads.encodePairingStatusResponse(response)));
        } catch (RuntimeException ignored) {
            // invalid request or the connection is already broken
        } finally {
            connection.close();
        }
    }

    @Override
    public void onClosed(FrameConnection connection) {
    }

    @Override
    public void onError(FrameConnection connection, IOException error) {
    }
}
