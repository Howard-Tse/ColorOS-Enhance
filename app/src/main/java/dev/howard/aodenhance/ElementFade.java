package dev.howard.aodenhance;

/** Drawing-only fade; never changes the alpha/visibility used by firmware logic. */
final class ElementFade {
    enum Change { KEEP, SNAP, ANIMATE }
    private float opacity = 1f;
    private float start = 1f;
    private boolean hidden;
    private boolean animating;

    Change retarget(boolean hide, boolean animate) {
        if (hidden == hide && (animate || !animating)) return Change.KEEP;
        hidden = hide;
        start = opacity;
        animating = animate && opacity != target();
        if (!animating) opacity = target();
        return animating ? Change.ANIMATE : Change.SNAP;
    }

    void advance(float fraction) {
        if (!animating) return;
        float bounded = Math.max(0, Math.min(1, fraction));
        opacity = start + (target() - start) * bounded;
        if (bounded == 1) { opacity = target(); animating = false; }
    }

    private float target() { return hidden ? 0f : 1f; }
    float opacity() { return opacity; }
    boolean isAnimating() { return animating; }
    long durationMillis() { return Math.max(1, Math.round(280f * Math.abs(target() - start))); }
    float compose(float nativeTransitionAlpha) { return nativeTransitionAlpha * opacity; }
    AlphaLedger.Clip clip(AlphaLedger.Clip nativeClip) {
        return hidden && !animating ? AlphaLedger.EMPTY : nativeClip;
    }

    static boolean shouldAnimate(String from, String to, boolean nativeAnimation) {
        if (!nativeAnimation || from.equals(to)) return false;
        // From an unlocked app/launcher the lockscreen elements were not visible: never reveal them to fade out.
        return (to.equals("PANORAMIC_AOD") && from.equals("KEYGUARD"))
                || (from.equals("PANORAMIC_AOD") && to.equals("KEYGUARD"));
    }
}
