package site.magics.phone.bridge;

/** Fail-closed and opt-in. A call ending always cancels prior activation. */
public final class CallSessionGate {
    private boolean userArmed;
    private boolean callActive;
    private boolean audioCapable;
    private boolean remotePartyInformed;

    public synchronized void setUserArmed(boolean armed) { userArmed = armed; }
    public synchronized void setCallActive(boolean active) {
        callActive = active;
        if (!active) { userArmed = false; remotePartyInformed = false; }
    }
    public synchronized void setAudioCapable(boolean capable) { audioCapable = capable; }
    public synchronized void setRemotePartyInformed(boolean informed) {
        remotePartyInformed = informed;
    }
    public synchronized boolean canStream() {
        return userArmed && callActive && audioCapable && remotePartyInformed;
    }
    public synchronized void stop() {
        userArmed = false;
        callActive = false;
        remotePartyInformed = false;
    }
}
