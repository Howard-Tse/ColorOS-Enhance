package dev.lsposed.fluidcloud;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=36, manifest=Config.NONE)
public class CapsuleSizeAndOrderTest {
    @Test public void defaultsAndFeatureMasterLeaveBothFeaturesOff() {
        var defaults=CloudConfig.from(Map.of("enabled",true));
        assertTrue(defaults.capsuleSize().original());assertFalse(defaults.selectedCardFirst());
        var disabled=CloudConfig.from(Map.of("capsule_size_enabled",true,"capsule_width_percent",150,
                "capsule_height_percent",120,"selected_card_first",true));
        assertTrue(disabled.capsuleSize().original());assertFalse(disabled.selectedCardFirst());
    }
    @Test public void optionsSurviveOrientationAndWindowLayerCopies() {
        var config=CloudConfig.from(Map.of("enabled",true,"capsule_size_enabled",true,
                "capsule_width_percent",130,"capsule_height_percent",120,"selected_card_first",true));
        assertEquals(new CapsuleSize(true,130,120),config.forOrientation(true).capsuleSize());
        assertEquals(config.extras().size(),config.withCapsuleOnTop(true).capsuleSize());
        assertTrue(config.withCapsuleOnTop(true).selectedCardFirst());
        assertEquals(new CapsuleSize(true,50,200),new CapsuleSize(true,0,999));
    }
    @Test @Config(qualifiers="xxhdpi") public void resizingChangesNativeBoundsWithoutStretchingProviderContent() throws Exception {
        var api=api();var view=new Capsule(RuntimeEnvironment.getApplication());
        measure(api,view,new CapsuleSize(true,120,150),1000);
        assertEquals(240,view.getMeasuredWidth());assertEquals(60,view.getMeasuredHeight());
        view.layout(0,0,240,60);
        assertEquals(100,view.child.getWidth());assertEquals(40,view.child.getHeight());
        assertEquals(70,view.child.getLeft());assertEquals(10,view.child.getTop());
        assertEquals(1f,view.getScaleX(),0);assertEquals(1f,view.child.getScaleX(),0);
    }
    @Test public void resizingAlwaysStartsFromNativeMeasurementAndCanRestoreExactly() throws Exception {
        var api=api();var view=new Capsule(RuntimeEnvironment.getApplication());
        for(int i=0;i<8;i++){measure(api,view,new CapsuleSize(true,150,100),1000);assertEquals(300,view.getMeasuredWidth());}
        measure(api,view,CapsuleSize.OEM,1000);view.layout(0,0,200,40);
        assertEquals(200,view.getMeasuredWidth());assertEquals(40,view.getMeasuredHeight());
        assertEquals(0,view.child.getLeft());assertEquals(0,view.child.getTop());
        api.close();
    }
    @Test public void enlargementRespectsAvailableWidthAndStatusBarHeight() throws Exception {
        var api=api();var view=new Capsule(RuntimeEnvironment.getApplication());
        measure(api,view,new CapsuleSize(true,200,200),220);
        assertEquals(220,view.getMeasuredWidth());
        int id=view.getResources().getIdentifier("status_bar_height","dimen","android");
        int limit=id==0?40:Math.max(40,view.getResources().getDimensionPixelSize(id));
        assertTrue(view.getMeasuredHeight()<=limit);
    }
    @Test @Config(qualifiers="xhdpi") public void nativeTemplatesReflowAtTheEdgesWithoutStretchingContent() throws Exception {
        var api = api();
        var context = RuntimeEnvironment.getApplication();
        var template = new SplitTemplate(context);
        var capsule = new Capsule(context, template, new FrameLayout.LayoutParams(-2, -2));
        int nativeTextWidth = 200 / 2 - template.gap / 2 - template.getPaddingRight();
        int iconWidth = template.icon.getLayoutParams().width;
        float textSize = template.text.getTextSize();

        measure(api, capsule, new CapsuleSize(true, 150, 150), 1000);
        capsule.layout(0, 0, capsule.getMeasuredWidth(), capsule.getMeasuredHeight());
        assertEquals(300, template.getWidth());
        assertTrue(template.getHeight() > 40);
        assertEquals(capsule.getHeight(), template.getHeight());
        assertEquals(template.getPaddingLeft(), template.icon.getLeft());
        assertEquals(template.getWidth() - template.getPaddingRight(), template.text.getRight());
        assertTrue(template.text.getWidth() > nativeTextWidth);
        assertEquals((template.getHeight() - template.icon.getHeight()) / 2, template.icon.getTop());
        assertEquals((template.getHeight() - template.text.getHeight()) / 2, template.text.getTop());
        assertEquals(iconWidth, template.icon.getWidth());
        assertEquals(textSize, template.text.getTextSize(), 0f);
        assertEquals(1f, template.getScaleX(), 0f);
        assertEquals(1f, template.getScaleY(), 0f);
        assertCameraGap(template);

        measure(api, capsule, new CapsuleSize(true, 70, 100), 1000);
        capsule.layout(0, 0, capsule.getMeasuredWidth(), capsule.getMeasuredHeight());
        assertEquals(140, template.getWidth());
        assertTrue(template.text.getWidth() < nativeTextWidth);
        assertEquals(iconWidth, template.icon.getWidth());
        assertCameraGap(template);

        measure(api, capsule, new CapsuleSize(true, 100, 100), 1000);
        capsule.layout(0, 0, capsule.getMeasuredWidth(), capsule.getMeasuredHeight());
        assertEquals(200, template.getWidth());
        assertEquals(40, template.getHeight());
    }
    @Test public void widthUpdateProbeUsesTheSameViewportAndDoesNotScaleTheParentMeasureTwice() throws Exception {
        var api = api();
        var context = RuntimeEnvironment.getApplication();
        var template = new SplitTemplate(context);
        var capsule = new Capsule(context, template, new FrameLayout.LayoutParams(-2, -2));
        int widthSpec = View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.AT_MOST);
        measure(api, capsule, new CapsuleSize(true, 180, 150), 1000, 260);
        capsule.layout(0, 0, capsule.getMeasuredWidth(), capsule.getMeasuredHeight());
        assertEquals(260, template.getWidth());
        int effectiveHeight = capsule.getHeight();

        var parentMeasure = api.beginNativeMeasure(capsule);
        try { assertFalse(api.isTemplateWidthProbe(template, widthSpec, 0)); }
        finally { api.endNativeMeasure(parentMeasure); }
        template.naturalWidth = 136;
        template.forceLayout();
        template.measure(widthSpec, 0);
        assertTrue(api.isTemplateWidthProbe(template, widthSpec, 0));
        var specs = api.templateProbeSpecs(template);
        assertNotNull(specs);
        assertEquals(245, View.MeasureSpec.getSize(specs[0]));
        assertEquals(effectiveHeight, View.MeasureSpec.getSize(specs[1]));
        template.measure(specs[0], specs[1]);
        template.layout(0, 0, 245, effectiveHeight);
        assertFalse(template.isLayoutRequested());
        parentMeasure = api.beginNativeMeasure(capsule);
        try { assertTrue(template.isLayoutRequested()); }
        finally { api.endNativeMeasure(parentMeasure); }

        var replacement = new SplitTemplate(context);
        capsule.removeView(template);
        capsule.addView(replacement, new FrameLayout.LayoutParams(-2, -2));
        measure(api, capsule, new CapsuleSize(true, 180, 150), 1000, 238);
        capsule.layout(0, 0, capsule.getMeasuredWidth(), capsule.getMeasuredHeight());
        assertFalse(api.isTemplateWidthProbe(template, widthSpec, 0));
        assertEquals(238, replacement.getWidth());
        assertCameraGap(replacement);
    }

    @Test @Config(qualifiers="xhdpi") public void sdkWrappersPassTheViewportThroughToTheFixedSizeTemplate() throws Exception {
        var context = RuntimeEnvironment.getApplication();
        assertWrappedViewport(new LinearLayout(context), new LinearLayout(context));
    }

    @Test @Config(qualifiers="xhdpi") public void notificationAndMediaWrappersPassThroughTheViewportAndCapTheWidthProbe() throws Exception {
        var context = RuntimeEnvironment.getApplication();
        assertWrappedViewport(new FrameLayout(context), new FrameLayout(context));
    }

    @Test @Config(qualifiers="xhdpi") public void colorOs16WrappedTemplatesReflowWithMixedSectionTypes() throws Exception {
        var context = RuntimeEnvironment.getApplication();
        assertWrappedViewport(new SplitTemplate(context), new FrameLayout(context));
    }

    private static void assertWrappedViewport(ViewGroup leading, ViewGroup trailing) throws Exception {
        var api = api();
        var context = RuntimeEnvironment.getApplication();
        var template = new SplitTemplate(context);
        // All native roots have two sections separated by a plain camera spacer.
        template.addView(leading, 0, new FrameLayout.LayoutParams(0, 0));
        template.addView(new View(context), 1, new FrameLayout.LayoutParams(0, 0));
        template.addView(trailing, 2, new FrameLayout.LayoutParams(0, 0));
        var renderer = new FrameLayout(context);
        var intrinsic = new FrameLayout.LayoutParams(200, 40);
        renderer.addView(template, intrinsic);
        var switcher = new FrameLayout(context);
        switcher.addView(renderer, new FrameLayout.LayoutParams(-2, -1));
        var sdk = new FrameLayout(context);
        sdk.addView(switcher, new FrameLayout.LayoutParams(-1, -1));
        var capsule = new Capsule(context, sdk, new FrameLayout.LayoutParams(-1, -1));

        measure(api, capsule, new CapsuleSize(true, 150, 150), 1000);
        capsule.layout(0, 0, capsule.getMeasuredWidth(), capsule.getMeasuredHeight());
        assertEquals(300, template.getWidth());
        assertEquals(capsule.getHeight(), template.getHeight());
        assertTrue(template.getHeight() > 40);
        assertCameraGap(template);
        assertEquals(200, intrinsic.width);
        assertEquals(40, intrinsic.height);

        intrinsic.width = 150;
        template.requestLayout();
        measure(api, capsule, new CapsuleSize(true, 150, 150), 280, 245);
        capsule.layout(0, 0, capsule.getMeasuredWidth(), capsule.getMeasuredHeight());
        assertEquals(245, template.getWidth());
        assertEquals(150, intrinsic.width);
        // The native content update can lay out just the fixed-size leaf,
        // leaving the outer wrapper and the capped width unchanged.
        int height = capsule.getHeight();
        template.measure(View.MeasureSpec.makeMeasureSpec(245, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(40, View.MeasureSpec.EXACTLY));
        template.layout(0, 0, 245, 40);
        assertEquals(height, sdk.getHeight());
        assertEquals(40, template.getHeight());
        capsule.layout(1, 0, 246, height);
        assertEquals(height, template.getHeight());
        int widthProbe = View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.AT_MOST);
        template.naturalWidth = 210;
        template.forceLayout();
        template.measure(widthProbe, 0);
        assertTrue(api.isTemplateWidthProbe(template, widthProbe, 0));
        int[] specs = api.templateProbeSpecs(template);
        assertNotNull(specs);
        assertEquals(245, View.MeasureSpec.getSize(specs[0]));
        assertEquals(capsule.getHeight(), View.MeasureSpec.getSize(specs[1]));
        template.measure(specs[0], specs[1]);
        template.layout(0, 0, 245, capsule.getHeight());
        var prior = api.beginNativeMeasure(capsule);
        try {
            assertFalse(api.isTemplateWidthProbe(template, widthProbe, 0));
            assertTrue(template.isLayoutRequested());
        } finally { api.endNativeMeasure(prior); }
        measure(api, capsule, CapsuleSize.OEM, 1000);
        capsule.layout(0, 0, capsule.getMeasuredWidth(), capsule.getMeasuredHeight());
        assertEquals(150, template.getWidth());
        assertEquals(40, template.getHeight());
    }

    private static void assertCameraGap(SplitTemplate template) {
        assertTrue(template.icon.getRight() <= (template.getWidth() - template.gap) / 2);
        assertTrue(template.text.getLeft() >= (template.getWidth() + template.gap) / 2);
    }
    @Test public void prioritizationDoesNotMutateNativeSourceAndPreservesOtherRelativeOrder() {
        List<String> original=List.of("stopwatch","media","delivery");
        assertEquals(List.of("media","stopwatch","delivery"),NativeCardOrder.prioritize(original,"media",x->x));
        assertEquals(List.of("stopwatch","media","delivery"),original);
        assertEquals(List.of("delivery","stopwatch","media"),NativeCardOrder.prioritize(original,"delivery",x->x));
    }
    @Test public void unknownBlankOrAmbiguousSelectionRetainsNativeOrder() {
        List<String> source=List.of("a","b","a");
        for(String key:new String[]{null,"","missing","a"})assertSame(source,NativeCardOrder.prioritize(source,key,x->x));
        List<String> unique=List.of("media","media:other");
        assertSame(unique,NativeCardOrder.prioritize(unique,"media",x->x));
        assertEquals(List.of("media:other","media"),NativeCardOrder.prioritize(unique,"media:other",x->x));
    }
    @Test public void launchKeepsVisibleOrderWhenNativeCapsuleHidesOrSwitches() {
        var selection=new NativeCardOrder.Selection();selection.change("media");
        assertTrue(selection.begin(true,"media"));
        selection.change(null);
        assertEquals("media",selection.key(true));
        selection.change("timer");
        assertEquals("media",selection.key(true));
        assertFalse(selection.begin(true,"timer"));
        assertEquals(List.of("media","timer"),NativeCardOrder.prioritize(List.of("timer","media"),selection.key(true),x->x));
        selection.end();
        assertEquals("timer",selection.key(true));
    }
    @Test public void launchFailureAndNextWindowCanReleaseWithoutLosingLatestSelection() {
        var selection=new NativeCardOrder.Selection();selection.change("media");
        assertFalse(selection.begin(false,"media"));assertFalse(selection.pinned());
        assertNull(selection.key(false));
        assertTrue(selection.begin(true,"media"));selection.change("timer");
        assertEquals("media",selection.key(false)); // Finish the visible transition even if disabled meanwhile.
        selection.end();assertNull(selection.key(false));
        assertEquals("timer",selection.key(true));
        assertTrue(selection.begin(true,"timer"));assertEquals("timer",selection.key(true));
    }
    @Test public void launchPinsLastPresentedPriorityEvenIfSelectionChangeWasQueuedBeforeTheIntent() {
        var selection=new NativeCardOrder.Selection();selection.change("media");
        // The native launch callback can update the capsule before BaseViewModel
        // is called; a pending adapter refresh has not changed visible holders.
        selection.change("timer");
        assertTrue(selection.begin(true,"media"));
        assertEquals("media",selection.key(true));
        selection.end();assertEquals("timer",selection.key(true));
    }
    private static NativeCapsuleSize api() throws Exception { return new NativeCapsuleSize(name->{
        if(name.equals(NativeCapsuleAppearance.VIEWS+"CapsuleView"))return Capsule.class;
        if(name.equals("androidx.constraintlayout.widget.ConstraintLayout"))return SplitTemplate.class;
        throw new ClassNotFoundException(name);
    }); }
    private static void measure(NativeCapsuleSize api,Capsule view,CapsuleSize options,int max) {
        measure(api, view, options, max, Integer.MAX_VALUE);
    }
    private static void measure(NativeCapsuleSize api,Capsule view,CapsuleSize options,int max,int externalLimit) {
        int width=View.MeasureSpec.makeMeasureSpec(max,View.MeasureSpec.AT_MOST);
        int height=View.MeasureSpec.makeMeasureSpec(1000,View.MeasureSpec.AT_MOST);
        view.forceLayout();view.measure(width,height);
        api.afterMeasure(view,width,height,options,externalLimit);
    }
    public static class Capsule extends FrameLayout {
        final View child;
        public Capsule(Context context) { this(context, new View(context), new LayoutParams(100, 40)); }
        Capsule(Context context, View child, LayoutParams params) {
            super(context);
            this.child = child;
            addView(child, params);
        }
        @Override public void onMeasure(int w,int h) { super.onMeasure(w,h);setMeasuredDimension(200,40); }
    }

    /** Same outside anchors and fixed center exclusion as the two native templates. */
    public static class SplitTemplate extends FrameLayout {
        final View icon;
        final TextView text;
        final int gap;
        int naturalWidth = 200;

        SplitTemplate(Context context) {
            super(context);
            float density = getResources().getDisplayMetrics().density;
            int pad = Math.round(4 * density);
            setPadding(pad, 0, pad, 0);
            gap = Math.round(16 * density);
            icon = new View(context);
            text = new TextView(context);
            text.setText("A long live-alert caption with room to reflow");
            text.setSingleLine(true);
            // Force a constrained line independently of Robolectric's font metrics.
            text.setMinWidth(500);
            int iconSize = Math.round(12 * density);
            addView(icon, new LayoutParams(iconSize, iconSize));
            addView(text, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 24));
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = resolveSize(naturalWidth, widthSpec);
            int height = resolveSize(40, heightSpec);
            int textWidth = Math.max(0, (width - gap) / 2 - getPaddingRight());
            icon.measure(exact(icon.getLayoutParams().width), exact(icon.getLayoutParams().height));
            text.measure(View.MeasureSpec.makeMeasureSpec(textWidth, View.MeasureSpec.AT_MOST), exact(24));
            setMeasuredDimension(width, height);
        }

        @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            place(icon, getPaddingLeft());
            place(text, getWidth() - getPaddingRight() - text.getMeasuredWidth());
        }

        private void place(View view, int left) {
            int top = (getHeight() - view.getMeasuredHeight()) / 2;
            view.layout(left, top, left + view.getMeasuredWidth(), top + view.getMeasuredHeight());
        }

        private static int exact(int size) { return View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY); }
    }
}
