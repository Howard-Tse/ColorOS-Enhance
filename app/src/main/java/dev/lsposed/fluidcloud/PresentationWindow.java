package dev.lsposed.fluidcloud;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Executes coordinator commands; never decides scenes or starts native geometry animators. */
final class PresentationWindow {
    static final int STATUS_BAR_SUB_PANEL = 2017;
    static final int DISPLAY_OVERLAY = 2026;
    static final String TITLE = "ColorOSEnhance-Cloud";
    private static final int LOCK_EXIT_MS = 150;
    private static final int LOCK_HANDOFF_LIMIT_MS = 280;
    private static final String LOCK_EXIT_LOG = "COSLockExit";
    final NativeCapsuleSource source;
    private final NativeOutputGate gate = new NativeOutputGate();
    private final WindowManager manager;
    private final WindowManager.LayoutParams params;
    private final SurfaceView view;
    private final Region rawTouch = new Region(), touch = new Region();
    private final Region previousTouch = new Region(), touchMask = new Region(), viewport = new Region();
    private final Region maskedSource = new Region();
    private final Path touchClip=new Path();
    private final Path touchBoundary=new Path();
    private final Rect sourceBounds = new Rect();
    private final RectF capsuleBounds = new RectF();
    private final RectF lastCapsuleBounds = new RectF();
    private CapsulePresentationGeometry.Profile lastCapsuleProfile;
    private RectF lastCapsuleAnchor;
    private int lastCapsuleOffset;
    private final Supplier<RectF> camera;
    private final BooleanSupplier allowed;
    private final Predicate<CloudPresentationCoordinator.Ticket> accepts;
    private final Runnable changed, pixels;
    private final Consumer<Throwable> failure;
    private final Object insets;
    private final Method removeInsets;
    private final ViewTreeObserver sourceObserver;
    private final ViewTreeObserver.OnPreDrawListener sourceDraw, readyDraw;
    private final boolean hardwareLockExitAvailable = LockExitRenderAnimation.available();
    private View shade;
    private boolean suppressShade, added, disposed, pending, dirty = true;
    private int busy;
    private long transferEpoch;
    private CloudPresentationCoordinator.Owner rendered = CloudPresentationCoordinator.Owner.NATIVE;
    private CloudPresentationCoordinator.Owner desired = CloudPresentationCoordinator.Owner.NATIVE;
    private CloudPresentationCoordinator.Ticket requestTicket;
    private Runnable ready;
    private CloudPresentationCoordinator.Frame frame;
    private CapsulePresentationGeometry.Geometry geometry;
    private CapsulePresentationGeometry.Geometry steadyGeometry;
    private LockExit lockExit;
    private boolean lockSourceHeld;
    private final Runnable lockHandoffTimeout = this::stopLockExitSafely;
    private final RectF steadySource = new RectF();
    private int steadyOffset;
    private float decorationOffsetY;
    private CapsulePresentationGeometry.Geometry maskedGeometry;
    private CloudCorners.Style maskedCorners;
    private int maskedWidth, maskedHeight;
    private float maskedDensity;
    FrameSync frameSync = new NativeFrameSync();
    Runnable prepareContent = () -> { };
    Supplier<CloudCorners.Style> corners = () -> CloudCorners.Style.OEM;
    IntSupplier verticalOffset = () -> 0;

    @SuppressLint({"WrongConstant","RtlHardcoded"})
    PresentationWindow(ViewGroup host, Supplier<RectF> camera, BooleanSupplier allowed,
            Predicate<CloudPresentationCoordinator.Ticket> accepts, Runnable changed, Runnable pixels,
            Consumer<Throwable> failure) throws ReflectiveOperationException {
        this(host,camera,allowed,accepts,changed,pixels,failure,false);
    }

    @SuppressLint({"WrongConstant","RtlHardcoded"})
    PresentationWindow(ViewGroup host, Supplier<RectF> camera, BooleanSupplier allowed,
            Predicate<CloudPresentationCoordinator.Ticket> accepts, Runnable changed, Runnable pixels,
            Consumer<Throwable> failure, boolean onTop) throws ReflectiveOperationException {
        source = new NativeCapsuleSource(host,true);source.independentHostPresentation=true;
        this.camera=camera;this.allowed=allowed;this.accepts=accepts;this.changed=changed;this.pixels=pixels;this.failure=failure;
        int type=onTop?DISPLAY_OVERLAY:STATUS_BAR_SUB_PANEL;
        Context context=host.getContext().createWindowContext(host.getDisplay(),type,null);
        manager=context.getSystemService(WindowManager.class);if(manager==null)throw new IllegalStateException("No window manager");
        view=new SurfaceView(context);view.setVisibility(View.INVISIBLE);view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        params=new WindowManager.LayoutParams(-1,Math.max(1,host.getRootView().getHeight()),type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        |WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,PixelFormat.TRANSLUCENT);
        params.gravity=Gravity.TOP|Gravity.LEFT;params.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        params.setTitle(TITLE);Reflect.call(Reflect.method(WindowManager.LayoutParams.class,"setFitInsetsTypes",void.class,int.class),params,0);
        Class<?> listener=Reflect.type(View.class.getClassLoader(),"android.view.ViewTreeObserver$OnComputeInternalInsetsListener");
        Class<?> info=Reflect.type(View.class.getClassLoader(),"android.view.ViewTreeObserver$InternalInsetsInfo");
        Method mode=Reflect.method(info,"setTouchableInsets",void.class,int.class);Field region=Reflect.field(info,"touchableRegion",Region.class);
        Method add=Reflect.method(ViewTreeObserver.class,"addOnComputeInternalInsetsListener",void.class,listener);
        removeInsets=Reflect.method(ViewTreeObserver.class,"removeOnComputeInternalInsetsListener",void.class,listener);
        insets=Reflect.listener(listener,(method,args)->{
            try {Reflect.call(mode,args[0],3);((Region)Reflect.get(region,args[0])).set(touch);}
            catch(RuntimeException|LinkageError error){fail(error);}
        });
        sourceObserver=host.getViewTreeObserver();
        sourceDraw=()->{
            if(!disposed && rendered==CloudPresentationCoordinator.Owner.MODULE && busy==0 && source.hasPendingPixels())pixels.run();
            return true;
        };
        readyDraw=()->{ try {transferIfReady();}catch(RuntimeException|LinkageError error){fail(error);}return true;};
        sourceObserver.addOnPreDrawListener(sourceDraw);
        view.getViewTreeObserver().addOnPreDrawListener(readyDraw);Reflect.call(add,view.getViewTreeObserver(),insets);
        view.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(l!=ol || t!=ot || r!=or || b!=ob){dirty=true;changed.run();}});
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
            public void onViewAttachedToWindow(View v){changed.run();v.post(()->{if(!disposed){transferIfReady();changed.run();}});}
            public void onViewDetachedFromWindow(View v){changed.run();}
        });
        try {manager.addView(view,params);added=true;}catch(RuntimeException|LinkageError error){dispose();throw error;}
    }
    boolean isBusy(){return busy!=0;}
    boolean contentVisible(){return !disposed && rendered==CloudPresentationCoordinator.Owner.MODULE
            && view.getVisibility()==View.VISIBLE && frame!=null && frame.fraction()>.03;}
    boolean hasLockHandoff() { return lockExit != null || lockSourceHeld; }
    void beginLockExit(BooleanSupplier displayVisible, String signal) {
        if (hasLockHandoff()) return;
        boolean shown = contentVisible();
        boolean displayOn = displayVisible.getAsBoolean();
        boolean animations = ValueAnimator.areAnimatorsEnabled();
        if (pending || !shown || geometry == null || !displayOn || !animations) {
            Log.i(LOCK_EXIT_LOG, "skip " + signal + " pending=" + pending + " shown=" + shown
                    + " geometry=" + (geometry != null) + " displayOn=" + displayOn + " animations=" + animations);
            return;
        }
        RectF bounds = new RectF(geometry.bounds());
        RectF anchor = camera.get();
        float endX = anchor == null || anchor.isEmpty() ? bounds.centerX() : anchor.centerX();
        LockExit exit = new LockExit(bounds, endX, corners.get(), displayVisible);
        lockExit = exit;
        lockSourceHeld = true;
        view.postDelayed(lockHandoffTimeout, LOCK_HANDOFF_LIMIT_MS);
        if (view.isHardwareAccelerated()) view.getViewTreeObserver().registerFrameCommitCallback(exit.firstCommit);
        Log.i(LOCK_EXIT_LOG, "start " + signal + " engine=" + exit.engine());
        if (exit.hardware == null) exit.animator.start();
        else view.invalidate();
    }
    private void finishLockVisual(String reason) {
        LockExit exit = lockExit;
        if (exit == null) return;
        lockExit = null;
        Log.i(LOCK_EXIT_LOG, "finish " + reason + " engine=" + exit.engine() + " uiDraws=" + exit.draws
                + " submitted=" + exit.submitted + " tailSubmitted=" + exit.tailSubmitted
                + " elapsedMs=" + (SystemClock.uptimeMillis() - exit.startedAt));
        ViewTreeObserver observer = view.getViewTreeObserver();
        if (observer.isAlive()) {
            observer.unregisterFrameCommitCallback(exit.firstCommit);
            observer.unregisterFrameCommitCallback(exit.tailCommit);
        }
        try { exit.cancel(); }
        finally {
            if (!lockSourceHeld) view.removeCallbacks(lockHandoffTimeout);
            updateVisibility();
            view.invalidate();
        }
    }
    void endLockExit(String reason) {
        if (!hasLockHandoff()) return;
        view.removeCallbacks(lockHandoffTimeout);
        try { finishLockVisual(reason); }
        finally {
            lockSourceHeld = false;
            if (rendered == CloudPresentationCoordinator.Owner.NATIVE) gate.restore();
        }
    }
    private void stopLockExitSafely() {
        try { endLockExit("handoff-timeout"); }
        catch (RuntimeException | LinkageError error) { fail(error); }
    }
    void restoreHiddenLockSource() {
        if (!lockSourceHeld) return;
        View host = source.host;
        if (!host.isShown() || host.getWindowVisibility() != View.VISIBLE || host.getAlpha() <= 0f) {
            // Once the status bar has actually hidden the old host, it can
            // take back its alpha without flashing behind the departing pill.
            gate.restore(host);
            lockSourceHeld = false;
            if (lockExit == null) view.removeCallbacks(lockHandoffTimeout);
        }
    }
    void nativeTargets(View shade,boolean suppress){
        if(this.shade!=shade || suppressShade!=suppress)gate.restore(this.shade);
        this.shade=shade;suppressShade=suppress;
        if(rendered!=CloudPresentationCoordinator.Owner.NATIVE)hideNative();
    }
    boolean suppressesNativeOutput(Object target){return !disposed && rendered!=CloudPresentationCoordinator.Owner.NATIVE
            && (target==source.host || suppressShade && (target==shade || target instanceof View child
                && child.getParent()==shade && child.getClass().getSimpleName().equals("FakePluginFrame")));}
    void sourceChanged(boolean layout){dirty|=layout;if(layout)source.structureChanged();if(!disposed)pixels.run();}
    void repaintPixels(){
        if(!contentVisible())return;
        // A waveform update changes only the bars. Real source layout and pose
        // changes still go through the coordinator and rebuild the touch region.
        view.postInvalidateOnAnimation();
    }
    void prepare(){
        if(disposed || !allowed.getAsBoolean())return;
        busy++;
        try {
            if(dirty || source.host.isLayoutRequested()){dirty=false;source.structureChanged();prepareContent.run();}
            int height=source.viewportHeight(view,Math.max(view.getResources().getDisplayMetrics().heightPixels,source.host.getRootView().getHeight()),
                    verticalOffset.getAsInt());
            if(height>0 && params.height!=height){params.height=height;manager.updateViewLayout(view,params);}
            source.refresh(view,rawTouch);
        }finally{busy--;}
    }
    void render(CloudPresentationCoordinator.Frame next){
        if(disposed || !accepts.test(next.ticket()))return;
        frame=next;updateGeometry();view.invalidate();
    }
    private void updateGeometry(){
        geometry=resolveGeometry();
        boolean inputAvailable = frame != null && (!frame.collapsing() || frame.pose() == null
                || frame.pose().body() > 0 && source.hasVisibleCapsules());
        if(lockExit==null && rendered==CloudPresentationCoordinator.Owner.MODULE && geometry!=null && inputAvailable && accepts.test(frame.ticket())){
            CloudCorners.Style style=corners.get();
            int width=view.getWidth(),height=view.getHeight();
            float density=view.getResources().getDisplayMetrics().density;
            if(geometry==maskedGeometry && width==maskedWidth && height==maskedHeight && density==maskedDensity
                    && style.equals(maskedCorners) && rawTouch.equals(maskedSource))return;
            previousTouch.set(touch);
            touchBoundary.rewind();rawTouch.getBoundaryPath(touchBoundary);touchBoundary.transform(geometry.forward());
            RectF bounds=geometry.bounds();
            CloudCorners.path(touchClip,bounds,corners(bounds,style),style.continuous());
            viewport.set(0,0,width,height);touchMask.setPath(touchClip,viewport);
            touch.setPath(touchBoundary,touchMask);
            maskedGeometry=geometry;maskedCorners=style;maskedWidth=width;maskedHeight=height;maskedDensity=density;
            maskedSource.set(rawTouch);
        }else{
            maskedGeometry=null;
            if(touch.isEmpty())return;
            previousTouch.set(touch);touch.setEmpty();
        }
        if(!previousTouch.equals(touch)){view.invalidate();var surface=view.getRootSurfaceControl();if(surface!=null)surface.setTouchableRegion(new Region(touch));}
    }
    private CapsulePresentationGeometry.Geometry resolveGeometry(){
        if(frame==null || CapsuleMotion.HIDDEN.equals(frame.pose()))return null;
        boolean live = rawTouch.getBounds(sourceBounds);
        if (!live && (!frame.collapsing() || lastCapsuleBounds.isEmpty())) return null;
        int offset = verticalOffset.getAsInt();
        RectF anchor = camera.get();
        if (live) {
            capsuleBounds.set(sourceBounds);
            if (source.hasVisibleCapsules() && (!frame.collapsing() || lastCapsuleBounds.isEmpty())) {
                if (!lastCapsuleBounds.equals(capsuleBounds) || !java.util.Objects.equals(lastCapsuleAnchor, anchor)
                        || lastCapsuleProfile == null) {
                    lastCapsuleBounds.set(capsuleBounds);
                    lastCapsuleAnchor = anchor == null ? null : new RectF(anchor);
                    lastCapsuleProfile = source.profile(view, anchor);
                }
                lastCapsuleOffset = offset;
            }
        }
        // The stock source can shrink or clear its own bounds before this
        // scene-driven exit ends. Complete the morph in the same coordinates.
        if (frame.collapsing() && !lastCapsuleBounds.isEmpty()) {
            capsuleBounds.set(lastCapsuleBounds);
            offset = lastCapsuleOffset;
        }
        if(CapsuleMotion.FULL.equals(frame.pose())){
            // A settled capsule keeps its source bounds; only a morph needs the native camera anchor.
            if(steadyGeometry==null || steadyOffset!=offset || !steadySource.equals(capsuleBounds)){
                steadySource.set(capsuleBounds);
                steadyOffset = offset;
                steadyGeometry = CapsulePresentationGeometry.offsetVertically(
                        CapsulePresentationGeometry.steady(capsuleBounds), offset);
            }
            return steadyGeometry;
        }
        var profile = frame.collapsing() ? lastCapsuleProfile : source.profile(view, anchor);
        CapsulePresentationGeometry.Geometry morph = frame.pose()==null?CapsulePresentationGeometry.resolve(capsuleBounds,anchor,frame.fraction())
                :CapsulePresentationGeometry.resolve(capsuleBounds,profile,frame.pose());
        decorationOffsetY = offset * (float) (frame.pose() == null ? frame.fraction() : frame.pose().body());
        return CapsulePresentationGeometry.offsetVertically(morph, decorationOffsetY);
    }
    private CloudCorners.Axes corners(RectF bounds,CloudCorners.Style style) {
        float width = bounds.width(), height = bounds.height();
        float pill = Math.min(width, height) / 2f;
        if(!style.enabled())return new CloudCorners.Axes(pill, pill);
        return CloudCorners.Axes.capsule(style.requested(view.getResources().getDisplayMetrics().density), width, height)
                .fromPill(width, height, geometry.fraction());
    }
    void request(CloudPresentationCoordinator.Owner next,CloudPresentationCoordinator.Ticket ticket,Runnable committed){
        if(disposed)return;
        requestTicket=ticket;desired=next;ready=committed;
        if(next!=CloudPresentationCoordinator.Owner.MODULE && !source.hasHandedOffTouch())source.cancelTouch();
        transferIfReady();complete();
    }
    private void transferIfReady(){
        if(disposed || pending || rendered==desired || !allowed.getAsBoolean())return;
        // Collapsed and expanded module output share the same native gate. Switching
        // between them needs only the staged module buffer, never a second handoff.
        if(rendered!=CloudPresentationCoordinator.Owner.NATIVE && desired!=CloudPresentationCoordinator.Owner.NATIVE){
            rendered=desired;if(rendered==CloudPresentationCoordinator.Owner.HIDDEN){clearCapsuleGeometry();source.clearMorph();source.restoreVisibility();}
            updateGeometry();updateVisibility();view.invalidate();complete();return;
        }
        if(!frameSync.ready(source.host,view))return;
        if(desired==CloudPresentationCoordinator.Owner.MODULE){
            prepare();
            if (!source.hasContent() || (verticalOffset.getAsInt() > 0 && view.getHeight() < params.height)) return;
        }
        long epoch=++transferEpoch;pending=true;busy++;
        // A deferred transaction takes the newest staged command at its first
        // mutation. Both roots then use that same command, including on reversal.
        CloudPresentationCoordinator.Owner[] target={null};
        Runnable select=()->{if(target[0]==null)target[0]=desired;};
        try {frameSync.transfer(source.host,shade,view,()->{
            if(disposed || epoch!=transferEpoch)return;select.run();
            if(target[0]==CloudPresentationCoordinator.Owner.NATIVE)gate.restore();else hideNative();
        },()->{
            if(disposed || epoch!=transferEpoch)return;select.run();
            rendered=target[0];
            if (rendered == CloudPresentationCoordinator.Owner.NATIVE) clearCapsuleGeometry();
            view.setVisibility(View.VISIBLE);updateGeometry();view.invalidate();
        },()->{
            if(disposed || epoch!=transferEpoch)return;
            try {pending=false;if(!allowed.getAsBoolean()){release();return;}transferIfReady();updateVisibility();
                if(!pending && rendered!=CloudPresentationCoordinator.Owner.MODULE){source.clearMorph();source.restoreVisibility();}complete();}
            catch(RuntimeException|LinkageError error){fail(error);}
        });}finally{busy--;}
    }
    private void hideNative(){gate.hide(source.host);if(suppressShade)gate.hide(shade);}
    private void complete(){
        if(ready==null || pending || rendered!=desired || !accepts.test(requestTicket))return;
        Runnable callback=ready;ready=null;callback.run();
    }
    private void updateVisibility(){
        boolean gesture=source.hasHandedOffTouch() && allowed.getAsBoolean();
        view.setVisibility(rendered==CloudPresentationCoordinator.Owner.MODULE || pending || gesture || lockExit!=null
                ?View.VISIBLE:View.INVISIBLE);
    }
    void cancelTouch(){source.cancelTouch();}
    private void clearCapsuleGeometry() {
        lastCapsuleBounds.setEmpty();
        lastCapsuleProfile = null;
        lastCapsuleAnchor = null;
    }
    void release(){
        if(disposed)return;
        transferEpoch++;pending=false;ready=null;rendered=desired=CloudPresentationCoordinator.Owner.NATIVE;
        frame=null;geometry=null;maskedGeometry=null;touch.setEmpty();rawTouch.setEmpty();
        clearCapsuleGeometry();
        Throwable error=null;
        try{source.cancelTouch();}catch(RuntimeException|LinkageError failure){error=failure;}
        try{source.clearMorph();}catch(RuntimeException|LinkageError failure){error=combine(error,failure);}
        try{source.restoreVisibility();}catch(RuntimeException|LinkageError failure){error=combine(error,failure);}
        try{
            // The shade belongs to keyguard immediately. Only the old unlocked
            // source stays suppressed until the final shell frame is gone.
            if(lockSourceHeld)gate.restore(shade);else gate.restore();
        }catch(RuntimeException|LinkageError failure){error=combine(error,failure);}
        try {
            if (lockExit == null) view.setVisibility(View.INVISIBLE);
            if (!hasLockHandoff()) view.requestLayout();
            var surface=view.getRootSurfaceControl();
            if (surface!=null) surface.setTouchableRegion(new Region());
        }
        catch(RuntimeException|LinkageError failure){error=combine(error,failure);}
        rethrow(error);
    }
    private void fail(Throwable error){
        try{endLockExit("render-failure");}catch(RuntimeException|LinkageError cleanup){if(cleanup!=error)error.addSuppressed(cleanup);}
        try{release();}catch(RuntimeException|LinkageError cleanup){if(cleanup!=error)error.addSuppressed(cleanup);}
        try{failure.accept(error);}catch(RuntimeException|LinkageError ignored){}
    }
    void dispose(){
        if(disposed)return;
        Throwable error=null;
        try{endLockExit("window-disposed");}catch(RuntimeException|LinkageError failure){error=failure;}
        try{release();}catch(RuntimeException|LinkageError failure){error=combine(error,failure);}
        disposed=true;
        try{if(sourceObserver.isAlive())sourceObserver.removeOnPreDrawListener(sourceDraw);}
        catch(RuntimeException|LinkageError failure){error=combine(error,failure);}
        try{ViewTreeObserver observer=view.getViewTreeObserver();if(observer.isAlive()){observer.removeOnPreDrawListener(readyDraw);Reflect.call(removeInsets,observer,insets);}}
        catch(RuntimeException|LinkageError failure){error=combine(error,failure);}
        try{if(added){added=false;manager.removeViewImmediate(view);}}
        catch(RuntimeException|LinkageError failure){error=combine(error,failure);}
        rethrow(error);
    }
    private static Throwable combine(Throwable first,Throwable next){if(first==null)return next;if(first!=next)first.addSuppressed(next);return first;}
    private static void rethrow(Throwable error){if(error instanceof RuntimeException runtime)throw runtime;if(error instanceof LinkageError linkage)throw linkage;}

    /** A visual-only exit; never keeps provider pixels, state or input alive on keyguard. */
    private final class LockExit {
        final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        final LockExitMotion motion;
        final CloudCorners.Style style;
        final BooleanSupplier displayVisible;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path outline = new Path();
        final RectF bounds = new RectF();
        LockExitRenderAnimation hardware;
        final long startedAt = SystemClock.uptimeMillis();
        float progress;
        int draws;
        boolean submitted, awaitingTailCommit, tailSubmitted;
        final Runnable firstCommit = () -> {
            if (lockExit != this || draws == 0) return;
            submitted = true;
            Log.i(LOCK_EXIT_LOG, "submitted frame elapsedMs=" + (SystemClock.uptimeMillis() - startedAt));
        };
        final Runnable tailCommit = () -> {
            if (lockExit != this) return;
            tailSubmitted = true;
            finishCompleted();
        };

        LockExit(RectF start, float endX, CloudCorners.Style style, BooleanSupplier displayVisible) {
            this.style = style;
            this.displayVisible = displayVisible;
            float radius = style.enabled() ? style.requested(view.getResources().getDisplayMetrics().density)
                    : Math.min(start.width(), start.height()) / 2f;
            motion = LockExitMotion.from(start, endX, radius);
            paint.setColor(Color.BLACK);
            long duration = Math.max(1, Math.round(LOCK_EXIT_MS / Math.max(1f, ValueAnimator.getDurationScale())));
            if (hardwareLockExitAvailable && view.isHardwareAccelerated() && !style.continuous()) {
                try {
                    hardware = new LockExitRenderAnimation(motion, paint, duration, () -> {
                        if (hardware != null) completed();
                    });
                }
                catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                    Log.i(LOCK_EXIT_LOG, "HWUI properties unavailable " + unavailable.getClass().getSimpleName());
                }
            }
            animator.setDuration(duration);
            animator.setInterpolator(new LinearInterpolator());
            animator.addUpdateListener(value -> {
                if (lockExit != this) return;
                try {
                    if (!displayVisible.getAsBoolean()) { endLockExit("display-not-on"); return; }
                    progress = value.getAnimatedFraction();
                    view.invalidate();
                } catch (RuntimeException | LinkageError error) { fail(error); }
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) { completed(); }
            });
        }

        String engine() { return hardware == null ? "ui-thread" : "render-thread"; }

        void completed() {
            if (lockExit != this || awaitingTailCommit) return;
            ViewTreeObserver observer = view.getViewTreeObserver();
            if (view.isHardwareAccelerated() && observer.isAlive() && displayVisible.getAsBoolean()) {
                // Animator completion can precede the final buffer. Record
                // the clear frame before destroying the overlay surface.
                awaitingTailCommit = true;
                observer.registerFrameCommitCallback(tailCommit);
                view.invalidate();
                return;
            }
            finishCompleted();
        }

        private void finishCompleted() {
            try { finishLockVisual("completed"); }
            catch (RuntimeException | LinkageError error) { fail(error); }
        }

        void cancel() {
            try { if (hardware != null) hardware.cancel(); }
            finally { animator.cancel(); }
        }

        void draw(Canvas canvas) {
            if (awaitingTailCommit || !displayVisible.getAsBoolean()) return;
            if (hardware != null) {
                try {
                    if (hardware.draw(canvas)) { recorded(); return; }
                } catch (RuntimeException | LinkageError unavailable) {
                    Log.i(LOCK_EXIT_LOG, "HWUI draw unavailable " + unavailable.getClass().getSimpleName());
                }
                // The old listener must not end the replacement animation.
                LockExitRenderAnimation previous = hardware;
                hardware = null;
                previous.cancel();
                if (lockExit != this) return;
                animator.start();
            }
            var shape = motion.frame(progress);
            bounds.set(shape.left(), shape.top(), shape.right(), shape.bottom());
            paint.setAlpha(Math.round(shape.alpha()));
            if (style.continuous()) {
                CloudCorners.path(outline, bounds, new CloudCorners.Axes(shape.radiusX(), shape.radiusY()), true);
                canvas.drawPath(outline, paint);
            } else canvas.drawRoundRect(bounds, shape.radiusX(), shape.radiusY(), paint);
            recorded();
        }

        private void recorded() {
            if (draws++ == 0) Log.i(LOCK_EXIT_LOG, "first frame elapsedMs=" + (SystemClock.uptimeMillis() - startedAt));
        }
    }

    private final class SurfaceView extends View {
        private final Paint black=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path animationClip=new Path();
        SurfaceView(Context context){super(context);black.setColor(Color.BLACK);}
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);
            if (lockExit != null) {
                try { lockExit.draw(canvas); } catch (RuntimeException | LinkageError error) { fail(error); }
                return;
            }
            if(disposed || rendered!=CloudPresentationCoordinator.Owner.MODULE || frame==null || !accepts.test(frame.ticket()) || geometry==null)return;
            int save=canvas.save();busy++;
            View previous = NativeCapsuleMarquee.presenting(this);
            try {
                source.presentVisibility();
                boolean morph=frame.collapsing()
                        || (frame.pose()==null?geometry.fraction()<.999f:!frame.pose().equals(CapsuleMotion.FULL));
                if(morph){
                    int clipSave=canvas.save();
                    RectF bounds=geometry.bounds();
                    CloudCorners.Style style=corners.get();
                    CloudCorners.Axes axes=corners(bounds,style);
                    CloudCorners.path(animationClip,bounds,axes,style.continuous());
                    canvas.clipPath(animationClip);
                    boolean shellOnly = frame.collapsing() && frame.pose()!=null
                            && (frame.pose().body() == 0 || !source.hasVisibleCapsules());
                    boolean nativeParts = !shellOnly && frame.pose()!=null
                            && source.drawMorph(canvas,view,geometry,style.enabled()?axes:null);
                    if(!nativeParts){
                        float opacity = shellOnly ? CapsuleMotion.dotOpacity((float)frame.pose().dot()) : 1f;
                        black.setAlpha(Math.round(255f * opacity));
                        canvas.drawPath(animationClip,black);
                        if (!shellOnly && geometry.contentAlpha() > 0f) {
                            canvas.saveLayerAlpha(bounds,Math.round(255*geometry.contentAlpha()));
                            canvas.concat(geometry.contentForward());source.draw(canvas);
                        }
                    }
                    canvas.restoreToCount(clipSave);
                    if(nativeParts){
                        canvas.translate(0f, decorationOffsetY);
                        source.drawMorphDecorations(canvas,view,geometry);
                    }
                }else{
                    source.clearMorph();canvas.concat(geometry.contentForward());source.draw(canvas);
                }
                // Animated provider drawables still advance their native clock when
                // the status-bar root is hidden, but that root no longer requests
                // buffers. Only their live output needs another module draw; static
                // content and ended animations do not create a permanent frame loop.
                if(source.hasRunningAnimation())postInvalidateOnAnimation();
            }catch(RuntimeException|LinkageError error){fail(error);}finally{
                NativeCapsuleMarquee.presenting(previous);
                busy--;canvas.restoreToCount(save);
            }
        }
        @SuppressLint("ClickableViewAccessibility")
        @Override public boolean onTouchEvent(MotionEvent event){
            if(disposed || lockExit!=null)return false;
            try {
                if(!allowed.getAsBoolean()){release();return false;}
                if(event.getActionMasked()==MotionEvent.ACTION_DOWN && (rendered!=CloudPresentationCoordinator.Owner.MODULE || geometry==null || frame==null || !accepts.test(frame.ticket()) || !touch.contains((int)event.getX(),(int)event.getY())))return false;
                MotionEvent copy=MotionEvent.obtain(event);
                try {if(geometry!=null)copy.transform(geometry.inverse());return source.dispatchTouchEvent(copy);}
                finally{copy.recycle();updateVisibility();changed.run();}
            }catch(RuntimeException|LinkageError error){fail(error);return false;}
        }
    }
}
