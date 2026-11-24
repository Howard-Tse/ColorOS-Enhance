package dev.lsposed.fluidcloud.pager;

import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Loaded against the plugin's RecyclerView. Only boot-classpath types cross the loader boundary. */
public final class GroupPageAdapter extends RecyclerView.Adapter<GroupPageAdapter.Holder> {
    private List<String> keys = List.of();
    private final BiConsumer<FrameLayout, String> bind;
    private final Consumer<FrameLayout> release;
    private final RecyclerView recycler;
    private final RecyclerView.OnScrollListener scroll;
    private final Consumer<String> longPress;

    public GroupPageAdapter(View list, BiConsumer<FrameLayout, String> bind, Consumer<FrameLayout> release,
            Runnable moved, Consumer<String> longPress) {
        this.bind = bind;
        this.release = release;
        this.longPress = longPress;
        recycler = (RecyclerView) list;
        scroll = new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView rv, int dx, int dy) { moved.run(); }
            @Override public void onScrollStateChanged(RecyclerView rv, int state) { moved.run(); }
        };
        recycler.addOnScrollListener(scroll);
    }
    public void replace(List<String> next) {
        if (keys.equals(next)) return;
        keys = List.copyOf(next);
        notifyDataSetChanged();
    }
    public void close() { recycler.removeOnScrollListener(scroll); }
    /** Position before any visual translation applied by the ROM's PageTransformer. */
    public float location() {
        if (!(recycler.getLayoutManager() instanceof LinearLayoutManager layout)) return -1f;
        int position = layout.findFirstVisibleItemPosition();
        View first = layout.findViewByPosition(position);
        if (first == null || first.getWidth() <= 0) return -1f;
        int displacement = recycler.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                ? first.getRight() - recycler.getWidth() + recycler.getPaddingRight()
                : recycler.getPaddingLeft() - first.getLeft();
        return position + Math.max(0f, Math.min(1f, (float) displacement / first.getWidth()));
    }
    @Override public int getItemCount() { return keys.size(); }
    @Override public Holder onCreateViewHolder(ViewGroup parent, int type) {
        PageFrame host = new PageFrame(parent.getContext(), longPress);
        host.setLayoutParams(new ViewGroup.LayoutParams(-1, -1));
        return new Holder(host);
    }
    @Override public void onBindViewHolder(Holder holder, int position) {
        String member = keys.get(position);
        PageFrame frame = (PageFrame) holder.itemView;
        frame.bindMember(member);
        bind.accept(frame, member);
    }
    @Override public void onViewRecycled(Holder holder) {
        PageFrame frame = (PageFrame) holder.itemView;
        frame.bindMember(null);
        release.accept(frame);
    }
    public static final class Holder extends RecyclerView.ViewHolder {
        Holder(FrameLayout item) { super(item); }
    }

    /** ViewPager2 needs a viewport-sized holder. Its notification needs its own natural
     * height even when the adjacent page currently sets a much shorter viewport. */
    static final class PageFrame extends FrameLayout {
        private final GestureDetector holds;
        private String member, pressedMember;

        PageFrame(Context context, Consumer<String> longPress) {
            super(context);
            holds = longPress == null ? null : new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                @Override public void onLongPress(MotionEvent event) {
                    String pressed = pressedMember;
                    pressedMember = null;
                    if (pressed != null && isAttachedToWindow()) longPress.accept(pressed);
                }
            });
            if (holds != null) holds.setOnDoubleTapListener(null);
        }

        void bindMember(String key) {
            if (member == null || !member.equals(key)) pressedMember = null;
            member = key;
        }

        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            if (holds != null) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN -> pressedMember = member;
                    case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> pressedMember = null;
                }
                // The notification stack cancels its own pending hold when the pager
                // requests interception. Observe the same stream even over child buttons.
                holds.onTouchEvent(event);
            }
            return super.dispatchTouchEvent(event);
        }

        @Override protected void onDetachedFromWindow() {
            pressedMember = null;
            super.onDetachedFromWindow();
        }
        @Override protected void measureChildWithMargins(View child, int parentWidth, int widthUsed,
                int parentHeight, int heightUsed) {
            super.measureChildWithMargins(child, parentWidth, widthUsed,
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), 0);
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            if (getChildCount() == 0) return;
            View card = getChildAt(0);
            int viewport = getHeight() - card.getTop() - getPaddingBottom();
            if (card.getHeight() <= 0 || viewport <= 0) return;
            // The pager clips each holder before applying its native page transition.
            // Fit the whole template, including its stroke, inside this frame. Keep
            // its measured height intact so the outer row can still interpolate it.
            float scale = Math.min(1f, (float) viewport / card.getHeight());
            card.setPivotY(0f);
            card.setPivotX(card.getWidth() * .5f);
            card.setScaleX(scale);
            card.setScaleY(scale);
        }
    }
}
