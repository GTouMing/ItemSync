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

import java.io.IOException;
import java.util.Objects;

public final class PairingStatusServer implements FrameHandler {

    private final GroupId groupId;
    private final NodeId nodeId;
    private final DeviceId deviceId;
    private final String fingerprint;
    private final PairingTrustProvider trust;

    public PairingStatusServer(GroupId groupId, NodeId nodeId, DeviceId deviceId,
                               String fingerprint, PairingTrustProvider trust) {
        this.groupId = Objects.requireNonNull(groupId, "groupId");
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.deviceId = Objects.requireNonNull(deviceId, "deviceId");
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        this.trust = Objects.requireNonNull(trust, "trust");
    }

    @Override
    public void onFrame(FrameConnection connection, Frame frame) {
        if (frame.type() != FrameType.PAIRING_STATUS) {
            connection.close();
            return;
        }
        boolean responded = false;
        try {
            PairingStatusRequest request = FramePayloads.decodePairingStatusRequest(frame.payload());
            if (!groupId.equals(request.groupId())) {
                return;
            }
            boolean trusts = trust.trusts(groupId, connection.peerFingerprint());
            PairingStatusResponse response = new PairingStatusResponse(groupId, nodeId, deviceId,
                    fingerprint, trusts);
            connection.send(new Frame(FrameType.PAIRING_STATUS_ACK,
                    FramePayloads.encodePairingStatusResponse(response)));
            responded = true;
            // The client closes after receiving the response; closing here races the write.
        } catch (RuntimeException ignored) {
            // invalid request or the connection is already broken
        } finally {
            if (!responded) {
                connection.close();
            }
        }
    }

    @Override
    public void onClosed(FrameConnection connection) {
    }

    @Override
    public void onError(FrameConnection connection, IOException error) {
    }
}
