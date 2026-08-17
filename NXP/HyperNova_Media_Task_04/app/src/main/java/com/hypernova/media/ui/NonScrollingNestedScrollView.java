package com.hypernova.media.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;

/**
 * HyperNova Media's page container.
 *
 * The cockpit screen itself is fixed: the driver should not need to
 * vertically scroll the entire application to reach primary controls.
 *
 * Child views remain fully interactive. RecyclerViews and horizontal
 * source/filter rows can still handle their own content when required.
 *
 * Programmatic scrollTo(0, 0) calls already present in MainActivity
 * remain harmless and keep the page pinned to its origin.
 */
public final class NonScrollingNestedScrollView extends NestedScrollView {

    public NonScrollingNestedScrollView(@NonNull Context context) {
        super(context);
    }

    public NonScrollingNestedScrollView(
            @NonNull Context context,
            @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public NonScrollingNestedScrollView(
            @NonNull Context context,
            @Nullable AttributeSet attrs,
            int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return false;
    }

    @Override
    public void fling(int velocityY) {
        // The page itself intentionally never flings/scrolls vertically.
    }
}
