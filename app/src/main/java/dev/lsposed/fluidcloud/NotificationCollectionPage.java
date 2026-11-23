package dev.lsposed.fluidcloud;

import android.animation.ValueAnimator;
import android.app.Notification;
import android.content.Context;
import android.graphics.Rect;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/** A focused view of the existing shade. The original rows stay in the stock notification pipeline. */
final class NotificationCollectionPage {
    private final NotificationCloudFeature feature;
    private final Class<?> shadeType;
    private final Class<?> stackType;
    private final Class<?> headerType;
    private final Field settingsButton;
    private final Field settingsStyleCache;
    private final Method styleButton, blurManager, releaseBlur;
    private final Method getScroll, setScroll, setStackAnimations;
    private final Field stackAnimations;
    private final OnBackInvokedCallback back = () -> exit(true);
    private FrameLayout shade;
    private ViewGroup stack;
    private ImageView returnButton, settingsAnchor;
    private View settingsHeader;
    private boolean stylingReturnButton, projected;
    private float buttonReveal;
    private int savedScroll;
    private final ViewTreeObserver.OnPreDrawListener updateControls = this::updateControls;
    private OnBackInvokedDispatcher dispatcher;
    private Set<String> keys = Set.of(), groups = Set.of();
    private Swap swap;
    private final NotificationGroupNavigation navigation;
    private NotificationCloudGrouping.Identity groupIdentity;
    private boolean focusOnly;
    private Set<String> targetKeys = Set.of();

    NotificationCollectionPage(NotificationCloudFeature feature, ClassLoader loader) throws ReflectiveOperationException {
        this.feature = feature;
        shadeType = Reflect.type(loader, "com.android.systemui.shade.NotificationShadeWindowView");
        stackType = Reflect.type(loader, "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout");
        headerType = Reflect.type(loader, "com.oplus.systemui.separate.OplusQSSimpleHeader");
        settingsButton = Reflect.field(headerType, "settingsButton", ImageView.class);
        Field cache;
        try { cache = Reflect.field(headerType, "lastSettingsBgApplyKey", Object.class); }
        catch (NoSuchFieldException olderFirmware) { cache = null; }
        settingsStyleCache = cache;
        styleButton = Reflect.method(headerType, "refreshSettingsButtonBackground", void.class);
        Class<?> manager = Reflect.type(loader, "com.oplus.systemui.notification.blur.ViewBlurManager");
        blurManager = Reflect.method(headerType, "getViewBlurManager", manager);
        releaseBlur = Reflect.method(manager, "releaseBlurProxyForView", void.class, View.class, boolean.class, String.class);
        getScroll = Reflect.method(stackType, "getOwnScrollY", int.class);
        setScroll = Reflect.method(stackType, "setOwnScrollY", void.class, int.class);
        setStackAnimations = Reflect.method(stackType, "setAnimationsEnabled", void.class, boolean.class);
        stackAnimations = Reflect.field(stackType, "mAnimationsEnabled", boolean.class);
        Class<?> row = Reflect.type(loader, "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow");
        Class<?> entry = Reflect.type(loader, "com.android.systemui.statusbar.notification.collection.NotificationEntry");
        Method rowEntry = Reflect.method(row, "getEntry", entry);
        NotificationGroupNavigation navigator;
        try { navigator = new NotificationGroupNavigation(feature, this, row, rowEntry, stackType); }
        catch (ReflectiveOperationException unavailable) {
            feature.failure("Native notification group navigation unavailable", unavailable);
            navigator = null;
        }
        navigation = navigator;
        Class<?> window = Reflect.type(loader, "com.android.systemui.shade.NotificationShadeWindowControllerImpl");
        Method visible = Reflect.method(window, "setPanelVisible", void.class, boolean.class);
        new NotificationScopedClear(feature, loader, stackType, row, rowEntry);
        feature.module.hook(visible).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            if (Boolean.FALSE.equals(chain.getArg(0))) close();
            return chain.proceed();
        });
        feature.module.hook(styleButton).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object result = chain.proceed();
            if (showing() && !stylingReturnButton && chain.getThisObject() == settingsHeader) styleReturnButton();
            return result;
        });
        try {
            Class<?> interactor = Reflect.type(loader, "com.android.systemui.shade.domain.interactor.ShadeBackActionInteractorImpl");
            Method onBack = Reflect.method(interactor, "onBackPressed", void.class);
            feature.module.hook(onBack).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                if (!showing()) return chain.proceed();
                exit(true);
                return null;
            });
        } catch (ReflectiveOperationException unavailable) { /* Window back dispatcher still handles Back. */ }
    }
    void open(View anchor, ViewGroup list) {
        if (showing() || !shadeType.isInstance(anchor.getRootView()) || !stackType.isInstance(list)) return;
        shade = (FrameLayout) anchor.getRootView();
        stack = list;
        if (navigation != null) navigation.cancel();
        open(null);
    }

    boolean canNavigateGroups() { return navigation != null; }

    void requestGroup(ViewGroup list, NotificationCloudGrouping.Identity identity) {
        if (navigation == null || showing() || !stackType.isInstance(list)
                || !shadeType.isInstance(list.getRootView())) return;
        shade = (FrameLayout) list.getRootView();
        stack = list;
        navigation.start(list, identity);
    }

    Set<String> displayedKeys() { return Set.copyOf(keys); }
    Set<String> targetKeys() { return targetKeys; }
    boolean readyToFocus() { return projected && swap == null; }

    boolean open(NotificationCloudGrouping.Identity identity) {
        if (showing() || shade == null || stack == null || stack.getRootView() != shade || !stack.isShown()
                || !feature.enabled() || !feature.unlocked()) return false;
        try {
            var anchor = findSettingsButton(shade);
            if (anchor == null) return false;
            View header = anchor;
            while (header != null && !headerType.isInstance(header))
                header = header.getParent() instanceof View parent ? parent : null;
            if (header == null || Reflect.get(settingsButton, header) != anchor) return false;
            var context = anchor.getContext();
            int arrow = context.getResources().getIdentifier("ic_chevron_left", "drawable", "com.android.systemui");
            if (arrow == 0) arrow = context.getResources().getIdentifier("coui_back_arrow", "drawable", "com.android.systemui");
            if (arrow == 0) return false;
            groupIdentity = identity;
            if (identity != null) {
                targetKeys = members(identity);
                focusOnly = !NotificationCloudGrouping.hasMatchingSummary(feature.liveNotifications(), targetKeys,
                        Set.copyOf(feature.collectedKeys()));
            } else focusOnly = false;
            if (!updateMembership()) return false;
            var button = createReturnButton(anchor);
            button.setImageDrawable(context.getDrawable(arrow));
            button.setImageTintList(anchor.getImageTintList());
            button.setScaleType(anchor.getScaleType());
            button.setContentDescription("返回通知中心");
            button.setOnClickListener(v -> exit(true));
            button.setFocusable(true);
            savedScroll = (int) Reflect.call(getScroll, stack);
            settingsHeader = header;
            settingsAnchor = anchor;
            returnButton = button;
            buttonReveal = 0f;
            int[] anchorPosition = positionInShade(anchor);
            var params = new FrameLayout.LayoutParams(anchor.getWidth(), anchor.getHeight(),
                    android.view.Gravity.TOP | android.view.Gravity.LEFT);
            params.leftMargin = anchorPosition[0] - anchor.getWidth() - dp(8) - shade.getPaddingLeft();
            params.topMargin = anchorPosition[1] - shade.getPaddingTop();
            shade.addView(button, params);
            styleReturnButton();
            button.setPadding(anchor.getPaddingLeft(), anchor.getPaddingTop(), anchor.getPaddingRight(), anchor.getPaddingBottom());
            updateControls();
            shade.getViewTreeObserver().addOnPreDrawListener(updateControls);
            dispatcher = shade.findOnBackInvokedDispatcher();
            if (dispatcher != null) dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, back);
            beginSwap(false, 0, () -> {
                projected = true;
                feature.collectionChanged();
            });
            return true;
        } catch (RuntimeException | LinkageError failure) {
            close();
            feature.failure("Native notification collection page unavailable", failure);
            return false;
        }
    }

    boolean showing() { return returnButton != null; }
    boolean projected() { return projected; }

    boolean contains(StatusBarNotification notification) {
        return !NotificationCloudPresentation.summary(notification)
                && (keys.contains(notification.getKey()) || (notification.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0
                && groups.contains(notification.getGroupKey()));
    }

    private Set<String> members(NotificationCloudGrouping.Identity identity) {
        Set<String> members = new HashSet<>();
        if (feature.rules().groupApps()) for (var item : feature.store.entries()) {
            if (item.ready() && identity.equals(NotificationCloudGrouping.identity(item.value()))) members.add(item.key());
        }
        return members;
    }

    private boolean updateMembership() {
        if (groupIdentity != null) {
            targetKeys = members(groupIdentity);
            if (targetKeys.isEmpty()) return false;
        }
        Set<String> next = focusOnly ? targetKeys : new HashSet<>(feature.collectedKeys());
        Set<String> nextGroups = new HashSet<>();
        for (String key : next) {
            var item = feature.store.get(key);
            if (item != null && item.value().isGroup()) nextGroups.add(item.value().getGroupKey());
        }
        if (keys.equals(next) && groups.equals(nextGroups)) return !keys.isEmpty();
        keys = next;
        groups = nextGroups;
        if (showing() && !keys.isEmpty()) feature.collectionChanged();
        return !keys.isEmpty();
    }

    void refresh() {
        if (!showing()) return;
        if (!feature.unlocked() || !feature.enabled() || !updateMembership()) close();
    }

    void close() { exit(false); }

    private void exit(boolean animate) {
        if (navigation != null) navigation.cancel();
        if (animate && showing()) {
            if (swap == null || !swap.leaving) beginSwap(true, savedScroll, this::restoreMainList);
            return;
        }
        if (swap != null) swap.finish();
        if (showing() || projected) restoreMainList();
    }

    private void restoreMainList() {
        if (navigation != null) navigation.restoreGroups();
        projected = false;
        if (dispatcher != null) dispatcher.unregisterOnBackInvokedCallback(back);
        dispatcher = null;
        if (shade.getViewTreeObserver().isAlive()) shade.getViewTreeObserver().removeOnPreDrawListener(updateControls);
        ImageView button = returnButton;
        returnButton = null;
        if (button != null) {
            try {
                Reflect.call(releaseBlur, Reflect.call(blurManager, settingsHeader), button, true, "Notification collection closed");
            } catch (RuntimeException | LinkageError unavailable) {
                feature.failure("Notification collection button blur cleanup unavailable", unavailable);
            }
            shade.removeView(button);
        }
        settingsAnchor = null;
        settingsHeader = null;
        keys = Set.of();
        groups = Set.of();
        targetKeys = Set.of();
        groupIdentity = null;
        focusOnly = false;
        feature.collectionChanged();
    }

    void rendered() {
        if (swap != null) swap.rendered();
        if (projected && navigation != null) navigation.rendered();
    }

    private void beginSwap(boolean leaving, int targetScroll, Runnable project) {
        float visibleAlpha = stack.getTransitionAlpha();
        if (swap != null) swap.finish();
        swap = new Swap(leaving, targetScroll, project);
        swap.start(visibleAlpha);
    }

    /** Change the live list while transparent, then reveal it only after SystemUI has laid it out. */
    private final class Swap {
        private final ViewGroup list = stack;
        private final int targetScroll;
        private final Runnable project;
        private final float listAlpha = list.getTransitionAlpha();
        private final boolean leaving;
        private ValueAnimator animator;
        private ViewTreeObserver.OnPreDrawListener preDraw;
        private boolean waiting, pausedStockAnimation;
        private final Runnable timeout = this::rendered;

        Swap(boolean leaving, int targetScroll, Runnable project) {
            this.leaving = leaving;
            this.targetScroll = targetScroll;
            this.project = project;
        }

        void start(float visibleAlpha) {
            if (ValueAnimator.areAnimatorsEnabled() && visibleAlpha > 0f) animate(visibleAlpha, 0f, 90, this::switchList);
            else switchList();
        }

        private void switchList() {
            if (swap != this) return;
            buttonReveal = leaving ? 0f : 1f;
            if (ValueAnimator.areAnimatorsEnabled()) list.setTransitionAlpha(0f);
            pausedStockAnimation = Boolean.TRUE.equals(Reflect.get(stackAnimations, list));
            if (pausedStockAnimation) Reflect.call(setStackAnimations, list, false);
            waiting = true;
            list.postDelayed(timeout, 240);
            project.run();
        }

        void rendered() {
            if (!waiting) return;
            waiting = false;
            list.removeCallbacks(timeout);
            if (!list.isAttachedToWindow()) { finish(); return; }
            Reflect.call(setScroll, list, targetScroll);
            preDraw = () -> {
                unregister();
                if (swap != this) return true;
                resumeStockAnimation();
                if (ValueAnimator.areAnimatorsEnabled() && listAlpha > 0f) animate(0f, listAlpha, 160, this::finish);
                else finish();
                return true;
            };
            list.getViewTreeObserver().addOnPreDrawListener(preDraw);
            list.invalidate();
        }

        private void animate(float from, float to, long duration, Runnable next) {
            animator = ValueAnimator.ofFloat(from, to);
            animator.setDuration(duration);
            animator.setInterpolator(new android.view.animation.PathInterpolator(.25f, .1f, .25f, 1f));
            float initialReveal = buttonReveal;
            float targetReveal = leaving ? 0f : 1f;
            animator.addUpdateListener(value -> {
                list.setTransitionAlpha((float) value.getAnimatedValue());
                buttonReveal = initialReveal + (targetReveal - initialReveal) * value.getAnimatedFraction();
            });
            animator.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator animation) {
                    if (animator != animation || swap != Swap.this) return;
                    animator = null;
                    next.run();
                }
            });
            animator.start();
        }

        private void resumeStockAnimation() {
            if (!pausedStockAnimation) return;
            pausedStockAnimation = false;
            Reflect.call(setStackAnimations, list, true);
        }

        void finish() {
            list.removeCallbacks(timeout);
            unregister();
            if (animator != null) {
                animator.removeAllListeners();
                animator.cancel();
                animator = null;
            }
            list.setTransitionAlpha(listAlpha);
            resumeStockAnimation();
            if (swap == this) {
                swap = null;
                if (!leaving && projected && navigation != null) navigation.rendered();
            }
        }

        private void unregister() {
            if (preDraw != null && list.getViewTreeObserver().isAlive()) list.getViewTreeObserver().removeOnPreDrawListener(preDraw);
            preDraw = null;
        }
    }

    private int[] positionInShade(View view) {
        int[] position = new int[2], origin = new int[2];
        view.getLocationInWindow(position);
        shade.getLocationInWindow(origin);
        return new int[]{position[0] - origin[0], position[1] - origin[1]};
    }

    private ImageView findSettingsButton(View root) {
        int id = root.getResources().getIdentifier("settings_button", "id", "com.android.systemui");
        return id == 0 ? null : findSettingsButton(root, id, new Rect());
    }

    private ImageView findSettingsButton(View view, int id, Rect bounds) {
        if (!view.isShown()) return null;
        if (view instanceof ImageView image && view.getId() == id && view.getGlobalVisibleRect(bounds)
                && bounds.centerX() >= shade.getWidth() / 2 && bounds.top < shade.getHeight() / 3) return image;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            ImageView found = findSettingsButton(group.getChildAt(i), id, bounds);
            if (found != null) return found;
        }
        return null;
    }

    private ImageView createReturnButton(ImageView anchor) {
        Context context = anchor.getContext();
        try {
            ClassLoader loader = headerType.getClassLoader();
            Class<?> type = Reflect.type(loader, "com.oplus.systemui.qs.OplusQsFooterSettingsSpotLightImageView");
            Class<?> delegate = Reflect.type(loader, "com.oplus.systemui.notification.material.NotificationSpotLightDelegate");
            Method touch = Reflect.method(delegate, "onTouchEvent", void.class, View.class, android.view.MotionEvent.class);
            ImageView button = (ImageView) type.getConstructor(Context.class).newInstance(context);
            // The stock panel touch bridge only targets the settings button's resource ID.
            button.setOnTouchListener((view, event) -> {
                // Register this pointer on DOWN. The shade forwards later events to the registered host.
                if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN)
                    Reflect.call(touch, null, view, event);
                return false;
            });
            return button;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            ImageView button = new ImageView(context);
            var mask = new android.graphics.drawable.GradientDrawable();
            mask.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            mask.setColor(android.graphics.Color.WHITE);
            var color = new android.util.TypedValue();
            boolean resolved = context.getTheme().resolveAttribute(android.R.attr.colorControlHighlight, color, true);
            button.setForeground(new android.graphics.drawable.RippleDrawable(
                    resolved && color.resourceId != 0 ? context.getColorStateList(color.resourceId)
                            : android.content.res.ColorStateList.valueOf(resolved ? color.data : 0x33ffffff), null, mask));
            return button;
        }
    }

    private void styleReturnButton() {
        // Use the same native style path but give the new view its own blur proxy and drawable.
        stylingReturnButton = true;
        try {
            Object oldStyle = settingsStyleCache == null ? null : Reflect.get(settingsStyleCache, settingsHeader);
            settingsButton.set(settingsHeader, returnButton);
            try {
                // Some firmware caches the material on the header, rather than on each button.
                if (settingsStyleCache != null) settingsStyleCache.set(settingsHeader, null);
                Reflect.call(styleButton, settingsHeader);
            } finally {
                settingsButton.set(settingsHeader, settingsAnchor);
                if (settingsStyleCache != null) settingsStyleCache.set(settingsHeader, oldStyle);
            }
        } catch (IllegalAccessException | RuntimeException | LinkageError failure) {
            feature.failure("Notification collection button styling unavailable", failure);
        } finally {
            stylingReturnButton = false;
        }
        if (returnButton.getBackground() == null) {
            int fallback = shade.getResources().getIdentifier("simple_qs_settings_button_background_drawable", "drawable", "com.android.systemui");
            if (fallback != 0) returnButton.setBackgroundResource(fallback);
        }
    }

    private void positionReturnButton() {
        int[] position = positionInShade(settingsAnchor);
        returnButton.setTranslationX(position[0] - settingsAnchor.getWidth() - dp(8) - returnButton.getLeft());
        returnButton.setTranslationY(position[1] - returnButton.getTop());
    }

    private boolean updateControls() {
        positionReturnButton();
        float alpha = 1f;
        for (View view = settingsAnchor; view != null && view != shade;
                view = view.getParent() instanceof View parent ? parent : null) {
            if (view.getVisibility() != View.VISIBLE) { alpha = 0f; break; }
            alpha *= view.getAlpha() * view.getTransitionAlpha();
        }
        alpha *= buttonReveal;
        returnButton.setAlpha(alpha);
        returnButton.setEnabled(alpha > 0f && settingsAnchor.isClickable() && (swap == null || !swap.leaving));
        return true;
    }
    private int dp(int value) { return Math.round(value * shade.getResources().getDisplayMetrics().density); }
    static CharSequence appName(Context context, String pkg) {
        try {
            var pm = context.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
        } catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException unavailable) {
            return pkg;
        }
    }
}
