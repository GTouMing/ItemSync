package github.gtouming.itemsync.transport.tls;

import github.gtouming.itemsync.domain.compat.Compat;
import github.gtouming.itemsync.domain.identity.GroupId;

import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.StandardConstants;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Carries the group id as the TLS SNI host name so one shared sync port can route
 * connections per group (ADR-0036).
 */
public final class ServerNameIndication {

    private ServerNameIndication() {
    }

    /**
     * Sets the group id as the SNI host name of an outbound handshake.
     */
    public static void apply(SSLParameters parameters, GroupId groupId) {
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(groupId, "groupId");
        parameters.setServerNames(Compat.list(new SNIHostName(groupId.value())));
    }

    /**
     * Returns the first SNI host name of the session, or empty when the session carries none.
     */
    public static Optional<String> hostName(SSLSession session) {
        if (!(session instanceof ExtendedSSLSession extended)) {
            // missing or a session implementation that does not expose the SNI extension
            return Optional.empty();
        }
        List<SNIServerName> names;
        try {
            names = extended.getRequestedServerNames();
        } catch (RuntimeException e) {
            // an unreadable SNI extension is reported as "no SNI", which callers reject
            return Optional.empty();
        }
        if (names == null) {
            return Optional.empty();
        }
        for (SNIServerName name : names) {
            if (name.getType() == StandardConstants.SNI_HOST_NAME) {
                return Optional.of(new String(name.getEncoded(), StandardCharsets.US_ASCII));
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the group id named by the session's SNI host name: empty when the session
     * carries no SNI, an unparsable value, or a name that is not a group id.
     */
    public static Optional<GroupId> group(SSLSession session) {
        return hostName(session).flatMap(ServerNameIndication::parse);
    }

    private static Optional<GroupId> parse(String hostName) {
        try {
            return Optional.of(GroupId.parse(hostName));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
