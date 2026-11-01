package github.gtouming.itemsync.cli;

public record PeerAddress(String host, int port, String fingerprint) {

    public static PeerAddress parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("peer address is required");
        }
        int fingerprintSeparator = value.lastIndexOf(':');
        int portSeparator = value.lastIndexOf(':', fingerprintSeparator - 1);
        if (portSeparator <= 0 || fingerprintSeparator <= portSeparator + 1
                || fingerprintSeparator == value.length() - 1) {
            throw new IllegalArgumentException("peer must be host:port:fingerprint, got: " + value);
        }
        String host = value.substring(0, portSeparator);
        int port;
        try {
            port = Integer.parseInt(value.substring(portSeparator + 1, fingerprintSeparator));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid peer port in: " + value, e);
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("peer port out of range: " + port);
        }
        String fingerprint = value.substring(fingerprintSeparator + 1);
        if (fingerprint.isBlank()) {
            throw new IllegalArgumentException("peer fingerprint is missing in: " + value);
        }
        return new PeerAddress(host, port, fingerprint);
    }
}
