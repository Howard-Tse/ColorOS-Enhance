package dev.howard.colorosenhance.controlcenter;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;
import android.widget.FrameLayout;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=36, application=Application.class, qualifiers="mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ControlCenterPageFadeTest {
    private final FrameLayout parent = new FrameLayout(RuntimeEnvironment.getApplication());
    private final Pager pager = new Pager(parent.getContext());
    private final ControlCenterPageFade fade = new ControlCenterPageFade(
            (view, canvas, tile) -> ((Pager) view).drawEdgeTile(canvas, tile), error -> {
                throw new AssertionError(error);
            });
    private int offset;

    @Test public void movingTilesStayOpaqueInsideTheStockViewport() {
        setup();
        Bitmap stock = draw();
        try (fade) {
            setOffset(50);
            Bitmap image = draw();
            for (int x = 100; x < 300; x++) assertEquals(stock.getPixel(x, 50), image.getPixel(x, 50));
            assertEquals(0, alpha(image, 79, 50));
            assertEquals(0, alpha(image, 320, 50));
            assertTrue(alpha(image, 90, 50) > 30 && alpha(image, 90, 50) < 220);
            assertTrue(alpha(image, 98, 50) > alpha(image, 90, 50));
            assertTrue(alpha(image, 302, 50) > alpha(image, 310, 50));
            assertTrue(parent.getClipChildren());
            assertTrue(pager.getClipToPadding());
        }
    }

    @Test public void slidingTheWholePanelWithAStationaryPagerKeepsNativeOverflow() {
        setup();
        parent.setClipChildren(false);
        pager.setClipToPadding(false);
        pager.setTranslationX(31);
        Bitmap stock = draw();
        assertEquals(255, alpha(stock, 125, 50));
        try (fade) {
            for (int position : new int[]{0, 200, -200}) {
                setOffset(position);
                assertTrue(stock.sameAs(draw()));
            }
            assertFalse(parent.getClipChildren());
            assertFalse(pager.getClipToPadding());
            assertEquals(0, pager.edgeDraws);
        }
    }

    @Test public void nativePaddingChangesRemainEffectiveDuringAndAfterPaging() {
        setup();
        pager.setPadding(8, 3, 8, 3);
        layout();
        Bitmap stock = draw();
        try (fade) {
            setOffset(65);
            Bitmap image = draw();
            for (int x = 108; x < 292; x++) assertEquals(stock.getPixel(x, 50), image.getPixel(x, 50));
            assertTrue(alpha(image, 102, 50) > 0 && alpha(image, 102, 50) < 255);
            assertEquals(0, alpha(image, 102, 1));
            pager.setClipToPadding(false);
            fade.sync(List.of());
            Bitmap unpadded = draw();
            setOffset(65);
            assertEquals(unpadded.getPixel(102, 50), draw().getPixel(102, 50));
        }
        assertFalse(pager.getClipToPadding());
        assertEquals(8, pager.getPaddingLeft());
        assertEquals(3, pager.getPaddingTop());
    }

    @Test public void exteriorStopsAtAdjacentControlsAndDisappearsWhenPageSettles() {
        setup();
        View sibling = new View(parent.getContext());
        sibling.setBackgroundColor(Color.RED);
        parent.addView(sibling, new FrameLayout.LayoutParams(94, 100));
        layout();
        Bitmap stock = draw();
        try (fade) {
            setOffset(-60);
            Bitmap image = draw();
            assertEquals(Color.RED, image.getPixel(93, 50));
            assertTrue(alpha(image, 97, 50) > 0);
            assertEquals(stock.getPixel(100, 50), image.getPixel(100, 50));
            offset = -200;
            fade.pagerDrawn(pager);
            assertTrue(stock.sameAs(draw()));
        }
    }

    @Test public void closingRemovesTheExtraDrawingAndPreservesTheParent() {
        setup();
        Bitmap stock = draw();
        setOffset(50);
        assertFalse(stock.sameAs(draw()));
        fade.close();
        assertTrue(stock.sameAs(draw()));
        assertSame(parent, pager.getParent());
        assertTrue(parent.getClipChildren());
        assertTrue(pager.getClipToPadding());
    }

    private void setup() {
        FrameLayout.LayoutParams pagerParams = new FrameLayout.LayoutParams(200, 100);
        pagerParams.leftMargin = 100;
        parent.addView(pager, pagerParams);
        FrameLayout.LayoutParams tileParams = new FrameLayout.LayoutParams(400, 100);
        tileParams.leftMargin = -100;
        View tile = new View(parent.getContext());
        tile.setBackgroundColor(Color.WHITE);
        pager.addView(tile, tileParams);
        layout();
    }

    private void setOffset(int value) {
        offset = value;
        fade.sync(List.of(new ControlCenterPageFade.Target(pager, () -> offset)));
    }

    private void layout() {
        parent.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY));
        parent.layout(0, 0, 400, 100);
    }

    private Bitmap draw() {
        Bitmap bitmap = Bitmap.createBitmap(400, 100, Bitmap.Config.ARGB_8888);
        parent.draw(new Canvas(bitmap));
        return bitmap;
    }

    private static int alpha(Bitmap bitmap, int x, int y) {
        return Color.alpha(bitmap.getPixel(x, y));
    }

    private static final class Pager extends FrameLayout {
        int edgeDraws;

        Pager(Context context) { super(context); }

        void drawEdgeTile(Canvas canvas, View tile) {
            edgeDraws++;
            super.drawChild(canvas, tile, getDrawingTime());
        }
    }
}
