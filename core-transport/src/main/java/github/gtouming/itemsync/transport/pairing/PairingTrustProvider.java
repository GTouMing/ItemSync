package github.gtouming.itemsync.transport.pairing;

import github.gtouming.itemsync.domain.identity.GroupId;

@FunctionalInterface
public interface PairingTrustProvider {

    boolean trusts(GroupId group, String peerFingerprint);
}
