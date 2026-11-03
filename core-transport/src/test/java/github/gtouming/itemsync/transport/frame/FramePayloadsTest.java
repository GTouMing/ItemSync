package github.gtouming.itemsync.transport.frame;

import github.gtouming.itemsync.domain.error.ErrorCode;
import github.gtouming.itemsync.domain.identity.BlobHash;
import github.gtouming.itemsync.domain.identity.DeviceId;
import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.NodeId;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FramePayloadsTest {

    private static final BlobHash HASH = BlobHash.parse("a".repeat(64));

    @Test
    void roundTripsHello() {
        Hello hello = new Hello(NodeId.of("node-a"), FrameCodec.PROTOCOL_VERSION, "abc",
                GroupId.parse("group001"));

        assertEquals(hello, FramePayloads.decodeHello(FramePayloads.encodeHello(hello)));
    }

    @Test
    void rejectsHelloWithoutGroupId() {
        byte[] payload = ("{\"nodeId\":\"node-a\",\"protocolVersion\":"
                + FrameCodec.PROTOCOL_VERSION + ",\"certificateFingerprint\":\"abc\"}")
                .getBytes(StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () -> FramePayloads.decodeHello(payload));
    }

    @Test
    void roundTripsPingAndPong() {
        assertEquals(7L, FramePayloads.decodePing(FramePayloads.encodePing(new Ping(7))).nonce());
        assertEquals(9L, FramePayloads.decodePong(FramePayloads.encodePong(new Pong(9))).nonce());
    }

    @Test
    void roundTripsBlobGet() {
        BlobGet request = new BlobGet(HASH, 123);

        assertEquals(request, FramePayloads.decodeBlobGet(FramePayloads.encodeBlobGet(request)));
    }

    @Test
    void roundTripsSuccessfulBlobResult() {
        BlobResult result = new BlobResult(HASH, true, 456, null);

        assertEquals(result, FramePayloads.decodeBlobResult(FramePayloads.encodeBlobResult(result)));
    }

    @Test
    void roundTripsFailedBlobResult() {
        BlobResult result = new BlobResult(HASH, false, 0, ErrorCode.BLOB_NOT_FOUND);

        assertEquals(result, FramePayloads.decodeBlobResult(FramePayloads.encodeBlobResult(result)));
    }

    @Test
    void roundTripsErrorMessage() {
        ErrorMessage error = new ErrorMessage(ErrorCode.INVALID_REQUEST, "bad request");

        assertEquals(error, FramePayloads.decodeError(FramePayloads.encodeError(error)));
    }

    @Test
    void roundTripsPairingStatusRequest() {
        PairingStatusRequest request = new PairingStatusRequest(GroupId.parse("group001"));

        assertEquals(request, FramePayloads.decodePairingStatusRequest(
                FramePayloads.encodePairingStatusRequest(request)));
    }

    @Test
    void roundTripsPairingStatusResponse() {
        PairingStatusResponse response = new PairingStatusResponse(
                GroupId.parse("group001"), NodeId.of("node-a"), DeviceId.of("device-a"),
                "fingerprint", true);

        assertEquals(response, FramePayloads.decodePairingStatusResponse(
                FramePayloads.encodePairingStatusResponse(response)));
    }

    @Test
    void rejectsBlankPairingFingerprint() {
        assertThrows(IllegalArgumentException.class, () -> new PairingStatusResponse(
                GroupId.parse("group001"), NodeId.of("node-a"), DeviceId.of("device-a"),
                "  ", true));
    }

    @Test
    void rejectsNonIntegerNumbers() {
        byte[] payload = "{\"nonce\":1.5}".getBytes(StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () -> FramePayloads.decodePing(payload));
    }

    @Test
    void rejectsMissingFields() {
        byte[] payload = "{\"nodeId\":\"node-a\"}".getBytes(StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () -> FramePayloads.decodeHello(payload));
    }

    @Test
    void rejectsNonObjectPayloads() {
        assertThrows(IllegalArgumentException.class,
                () -> FramePayloads.decodePing("[]".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,
                () -> FramePayloads.decodePing("not json".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsInconsistentBlobResults() {
        assertThrows(IllegalArgumentException.class,
                () -> new BlobResult(HASH, true, 1, ErrorCode.UNKNOWN));
        assertThrows(IllegalArgumentException.class,
                () -> new BlobResult(HASH, false, 1, null));
    }
}
