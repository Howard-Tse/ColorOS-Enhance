package dev.lsposed.fluidcloud;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

/** One long-press navigation. The shade can create or rebind rows after it starts opening. */
final class NotificationGroupNavigation implements ViewTreeObserver.OnPreDrawListener {
    private final NotificationCloudFeature feature;
    private final NotificationCollectionPage page;
    private final Class<?> row;
    private final Method entry, children, expanded, expand, childrenExpanded, setChildrenExpanded, scrollTo;
    private final java.lang.reflect.Field headerClick, shadeMoving;
    private final java.util.Map<View, Object> openedSummaries = new java.util.IdentityHashMap<>();
    private final Runnable timeout = this::cancel;
    private final Rect visible = new Rect();
    private ViewGroup stack;
    private ViewTreeObserver observer;
    private NotificationCloudGrouping.Identity identity;
    private boolean opened, rendered;
    private View scrollTarget;

    NotificationGroupNavigation(NotificationCloudFeature feature, NotificationCollectionPage page,
                                Class<?> row, Method entry, Class<?> stackType) throws ReflectiveOperationException {
        this.feature = feature;
        this.page = page;
        this.row = row;
        this.entry = entry;
        children = Reflect.method(row, "getAttachedChildren", List.class);
        expanded = Reflect.method(row, "isGroupExpanded", boolean.class);
        expand = Reflect.method(row, "setUserExpanded", void.class, boolean.class, boolean.class);
        childrenExpanded = Reflect.method(row, "areChildrenExpanded", boolean.class);
        setChildrenExpanded = Reflect.method(row, "setChildrenExpanded", void.class, boolean.class);
        java.lang.reflect.Field click = null, moving = null;
        try { click = Reflect.field(row, "mExpandClickListener", View.OnClickListener.class); }
        catch (NoSuchFieldException older) { /* Use the row expansion setter. */ }
        try { moving = Reflect.field(stackType, "mIsExpansionChanging", boolean.class); }
        catch (NoSuchFieldException older) { /* The collection transition still gates navigation. */ }
        headerClick = click;
        shadeMoving = moving;
        scrollTo = Reflect.method(stackType, "scrollTo", boolean.class, View.class);
    }

    void start(ViewGroup list, NotificationCloudGrouping.Identity group) {
        cancel();
        if (!list.isAttachedToWindow()) return;
        try {
            var status = list.getContext().getSystemService(Class.forName("android.app.StatusBarManager"));
            if (status == null) return;
            Reflect.call(Reflect.method(status.getClass(), "expandNotificationsPanel", void.class), status);
            stack = list;
            identity = group;
            observer = list.getViewTreeObserver();
            observer.addOnPreDrawListener(this);
            feature.main.postDelayed(timeout, 2500);
            list.invalidate();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            cancel();
            feature.failure("Could not open notification shade for group", failure);
        }
    }

    void rendered() {
        if (stack == null) return;
        rendered = true;
        stack.invalidate();
    }

    @Override public boolean onPreDraw() {
        if (stack == null) return true;
        try {
            if (!feature.enabled() || !feature.unlocked() || !feature.rules().groupApps() || !stack.isAttachedToWindow()) {
                cancel();
            } else if (!opened) {
                if (stack.getGlobalVisibleRect(visible)) opened = page.open(identity);
            } else if (page.readyToFocus() && (rendered || scrollTarget != null)
                    && (shadeMoving == null || !Boolean.TRUE.equals(Reflect.get(shadeMoving, stack)))) {
                if (scrollTarget == null) {
                    rendered = false;
                    scrollTarget = expandTargetRows(stack, page.targetKeys());
                    if (scrollTarget != null) stack.postInvalidateOnAnimation();
                } else {
                    // Wait for expanded children to be measured before asking the native stack to scroll.
                    if (scrollTarget.getParent() == stack) Reflect.call(scrollTo, stack, scrollTarget);
                    cancel();
                }
            }
        } catch (RuntimeException | LinkageError unavailable) {
            cancel();
            feature.failure("Could not focus native notification group", unavailable);
        }
        return true;
    }

    private View expandTargetRows(ViewGroup list, Set<String> keys) {
        View first = null;
        for (int i = 0; i < list.getChildCount(); i++) {
            View candidate = list.getChildAt(i);
            if (row.isInstance(candidate) && expandBranch(candidate, keys) && first == null) first = candidate;
        }
        return first;
    }

    private boolean expandBranch(View view, Set<String> keys) {
        boolean childMatches = false;
        List<?> nested = (List<?>) Reflect.call(children, view);
        if (nested != null) for (Object child : nested) {
            if (row.isInstance(child) && expandBranch((View) child, keys)) childMatches = true;
        }
        if (childMatches) {
            if (!Boolean.TRUE.equals(Reflect.call(expanded, view))) {
                var click = headerClick == null ? null : (View.OnClickListener) Reflect.get(headerClick, view);
                if (click != null) click.onClick(view);
                if (!Boolean.TRUE.equals(Reflect.call(expanded, view))) Reflect.call(expand, view, true, true);
                if (Boolean.TRUE.equals(Reflect.call(expanded, view))) openedSummaries.put(view, Reflect.call(entry, view));
            } else if (!Boolean.TRUE.equals(Reflect.call(childrenExpanded, view))) {
                Reflect.call(setChildrenExpanded, view, true);
            }
            return true;
        }
        Object current = Reflect.call(entry, view);
        return current != null && keys.contains(feature.notification(current).getKey());
    }

    void restoreGroups() {
        try {
            for (var group : openedSummaries.entrySet()) {
                View view = group.getKey();
                if (view.isAttachedToWindow() && Reflect.call(entry, view) == group.getValue()
                        && Boolean.TRUE.equals(Reflect.call(expanded, view))) Reflect.call(expand, view, false, true);
            }
        } catch (RuntimeException | LinkageError unavailable) {
            feature.failure("Could not restore notification group expansion", unavailable);
        } finally {
            openedSummaries.clear();
        }
    }

    void cancel() {
        feature.main.removeCallbacks(timeout);
        if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
        observer = null;
        stack = null;
        identity = null;
        scrollTarget = null;
        opened = false;
        rendered = false;
    }
}
