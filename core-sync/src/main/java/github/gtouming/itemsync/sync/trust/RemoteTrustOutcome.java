package github.gtouming.itemsync.sync.trust;

record RemoteTrustOutcome(RemoteTrust trust, RemoteTrustFailure failure, String detail) {

    static RemoteTrustOutcome trusted() {
        return new RemoteTrustOutcome(RemoteTrust.TRUSTED, null, null);
    }

    static RemoteTrustOutcome untrusted() {
        return new RemoteTrustOutcome(RemoteTrust.UNTRUSTED, null, null);
    }

    static RemoteTrustOutcome unknown(RemoteTrustFailure failure, String detail) {
        return new RemoteTrustOutcome(RemoteTrust.UNKNOWN, failure, detail);
    }
}
