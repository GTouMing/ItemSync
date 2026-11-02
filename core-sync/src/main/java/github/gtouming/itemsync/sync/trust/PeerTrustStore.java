package github.gtouming.itemsync.sync.trust;

import github.gtouming.itemsync.domain.identity.GroupId;

import java.io.IOException;

public interface PeerTrustStore {

    boolean isTrusted(GroupId group, String fingerprint);

    void trust(GroupId group, String fingerprint) throws IOException;

    void untrust(GroupId group, String fingerprint) throws IOException;

    void retainOnly(GroupId group) throws IOException;

    void clear() throws IOException;
}
