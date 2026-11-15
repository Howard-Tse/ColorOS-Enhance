package dev.howard.aodenhance;

/** Main-thread UI state; element drawing may outlive the interactive AOD scope. */
final class AodUiState {
    private String state = "UNKNOW_STATE";
    private boolean retainHiddenOnUnlock;

    boolean update(String next) {
        if (state.equals(next)) return false;
        // UNLOCK is emitted at authentication success, before the lockscreen has left the display.
        // These are lockscreen-only views: keep them hidden while the system removes that surface.
        retainHiddenOnUnlock = next.equals("UNLOCK") && (isPanoramic() || retainHiddenOnUnlock);
        state = next;
        return true;
    }

    String name() { return state; }
    boolean isPanoramic() { return state.equals("PANORAMIC_AOD"); }
    boolean hideElements() { return isPanoramic() || retainHiddenOnUnlock; }
}
