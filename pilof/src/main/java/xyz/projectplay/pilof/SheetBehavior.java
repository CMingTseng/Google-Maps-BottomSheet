package xyz.projectplay.pilof;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Parcel;
import android.os.Parcelable;
import android.support.annotation.IntDef;
import android.support.annotation.NonNull;
import android.support.design.widget.CoordinatorLayout;
import android.support.v4.content.ContextCompat;
import android.support.v4.os.ParcelableCompat;
import android.support.v4.os.ParcelableCompatCreatorCallbacks;
import android.support.v4.view.AbsSavedState;
import android.support.v4.view.MotionEventCompat;
import android.support.v4.view.NestedScrollingChild;
import android.support.v4.view.VelocityTrackerCompat;
import android.support.v4.view.ViewCompat;
import android.support.v4.widget.ViewDragHelper;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;

import static xyz.projectplay.pilof.R.styleable;

/**
 * Created by Nathan Reline on 8/25/16.
 * An interaction behavior plugin for an extended child view of {@link android.support.design.widget.BottomSheetBehavior}
 * to work similar to the Google Maps bottom sheet.
 */
public class SheetBehavior<V extends View> extends CoordinatorLayout.Behavior<V> {

    private final String TAG = this.getClass().getSimpleName();

    /**
     * Callback for monitoring events about bottom sheets.
     */
    public abstract static class SheetCallback {

        /**
         * Called when the bottom sheet changes its state.
         *
         * @param sheet The bottom sheet view.
         * @param newState    The new state. This will be one of {@link #STATE_DRAGGING},
         *                    {@link #STATE_SETTLING}, {@link #STATE_EXPANDED},
         *                    {@link #STATE_COLLAPSED}, or {@link #STATE_HIDDEN}.
         */
        public abstract void onStateChanged(@NonNull View sheet, @State int newState);

        /**
         * Called when the bottom sheet is being dragged.
         *
         * @param sheet The bottom sheet view.
         * @param slideOffset The new offset of this bottom sheet within its range, from 0 to 1
         *                    when it is moving upward, and from 0 to -1 when it moving downward.
         */
        public abstract void onSlide(@NonNull View sheet, float slideOffset);
    }

    /**
     * The bottom sheet is dragging.
     */
    public static final int STATE_DRAGGING = 1;

    /**
     * The bottom sheet is settling.
     */
    public static final int STATE_SETTLING = 2;

    /**
     * The bottom sheet is expanded.
     */
    public static final int STATE_EXPANDED = 3;

    /**
     * The bottom sheet is collapsed.
     */
    public static final int STATE_COLLAPSED = 4;

    /**
     * The bottom sheet is hidden.
     */
    public static final int STATE_HIDDEN = 5;

    /**
     * The bottom sheet is anchored.
     */
    public static final int STATE_ANCHORED = 6;

    /** @hide */
    @IntDef({STATE_EXPANDED, STATE_COLLAPSED, STATE_DRAGGING, STATE_SETTLING, STATE_HIDDEN, STATE_ANCHORED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface State {}

    private static final float HIDE_THRESHOLD = 0.5f;

    private static final float HIDE_FRICTION = 0.1f;

    private float mMaximumVelocity;

    private int mPeekHeight;

    private int mAnchorOffset;

    private int mMinOffset;

    private int mMaxOffset;

    private boolean mHideable;

    private boolean mShouldAnchor;

    private boolean mSkipCollapsed;

    private int mCurrentColor;

    private int mCollapsedColor;

    private int mAnchorColor;

    @State
    private int mState = STATE_HIDDEN;

    private ViewDragHelper mViewDragHelper;

    private boolean mIgnoreEvents;

    private int mLastNestedScrollDy;

    private boolean mNestedScrolled;

    private int mParentHeight;

    private WeakReference<V> mViewRef;

    private WeakReference<View> mNestedScrollingChildRef;

    private SheetCallback mCallback;

    private VelocityTracker mVelocityTracker;

    private int mActivePointerId;

    private int mInitialY;

    private boolean mTouchingScrollingChild;

    LinearLayout bottomsheet;

    boolean stateFlag = false;
    View headerLayout;

    View contentLayout;

    View parallax;

    private Context mContext;

    /**
     * Default constructor for instantiating SheetBehaviors.
     */
    public SheetBehavior() {

    }

    /**
     * Default constructor for inflating SheetBehaviors from layout.
     *
     * @param context The {@link Context}.
     * @param attrs   The {@link AttributeSet}.
     */
    public SheetBehavior(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
        Resources res = context.getResources();
        TypedArray a = context.obtainStyledAttributes(attrs,
                styleable.BottomSheetBehavior_Layout);
        setPeekHeight(a.getDimensionPixelSize(
                styleable.BottomSheetBehavior_Layout_behavior_peekHeight,
                res.getDimensionPixelSize(R.dimen.peekHeight)));
        setHideable(a.getBoolean(styleable.BottomSheetBehavior_Layout_behavior_hideable, false));
        setSkipCollapsed(a.getBoolean(styleable.BottomSheetBehavior_Layout_behavior_skipCollapsed,
                false));
        setAnchorHeight(a.getDimensionPixelSize(styleable.BottomSheetBehavior_Layout_behavior_anchorOffset,
                        res.getDimensionPixelSize(R.dimen.anchorOffset)));

        setCollapsedColor(a.getColor(styleable.BottomSheetBehavior_Layout_behavior_collapsedColor, Color.WHITE));
        setAnchorColor(a.getColor(styleable.BottomSheetBehavior_Layout_behavior_anchorColor,
                ContextCompat.getColor(context, R.color.anchorColor)));

        bottomsheet = (LinearLayout) LayoutInflater.from(context).inflate(R.layout.sheet, null);
        headerLayout = bottomsheet.findViewById(R.id.header);
        contentLayout = bottomsheet.findViewById(R.id.content);

        if (a.hasValue(styleable.BottomSheetBehavior_Layout_behavior_header_layout)) {
            bottomsheet.removeView(headerLayout);
            headerLayout = LayoutInflater.from(context).inflate(a.getResourceId(styleable.BottomSheetBehavior_Layout_behavior_header_layout, 0),
                    null);
            bottomsheet.addView(headerLayout, 0);
        }
        bottomsheet.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (mState == STATE_ANCHORED) stateFlag = true;
                updateHeaderColor(mState == STATE_COLLAPSED ? mAnchorColor : mCollapsedColor);
                setState(mState == STATE_COLLAPSED ? STATE_ANCHORED : STATE_COLLAPSED);
            }
        });
        Drawable background = headerLayout.getBackground();
        if (background instanceof ColorDrawable)
            mCurrentColor = ((ColorDrawable) background).getColor();
        else
            mCurrentColor = mCollapsedColor;

        if (a.hasValue(styleable.BottomSheetBehavior_Layout_behavior_content_layout)) {
            bottomsheet.removeView(contentLayout);
            contentLayout = LayoutInflater.from(context).inflate(a.getResourceId(styleable.BottomSheetBehavior_Layout_behavior_content_layout, 0),
                    null);
            bottomsheet.addView(contentLayout, 1);
        }

        a.recycle();

        ViewConfiguration configuration = ViewConfiguration.get(context);
        mMaximumVelocity = configuration.getScaledMaximumFlingVelocity();
    }

    @Override
    public Parcelable onSaveInstanceState(CoordinatorLayout parent, V child) {
        return new SavedState(super.onSaveInstanceState(parent, child), mState);
    }

    @Override
    public void onRestoreInstanceState(CoordinatorLayout parent, V child, Parcelable state) {
        SavedState ss = (SavedState) state;
        super.onRestoreInstanceState(parent, child, ss.getSuperState());
        // Intermediate states are restored as collapsed state
        if (ss.state == STATE_DRAGGING || ss.state == STATE_SETTLING) {
            mState = STATE_COLLAPSED;
        } else {
            mState = ss.state;
        }
    }

    @Override
    public boolean onLayoutChild(CoordinatorLayout parent, V child, int layoutDirection) {
        if (ViewCompat.getFitsSystemWindows(parent) && !ViewCompat.getFitsSystemWindows(child)) {
            ViewCompat.setFitsSystemWindows(child, true);
        }
        int savedTop = child.getTop();
        // First let the parent lay it out
        parent.onLayoutChild(child, layoutDirection);
        // Offset the bottom sheet
        mParentHeight = parent.getHeight();
        mMinOffset = Math.max(0, mParentHeight - child.getHeight());
        mMaxOffset = Math.max(mParentHeight - mPeekHeight, mMinOffset);
        if (mState == STATE_EXPANDED) {
            ViewCompat.offsetTopAndBottom(child, mMinOffset);
        } else if (mHideable && mState == STATE_HIDDEN) {
            ViewCompat.offsetTopAndBottom(child, mParentHeight);
        } else if (mState == STATE_COLLAPSED) {
            ViewCompat.offsetTopAndBottom(child, mMaxOffset);
        } else if (mState == STATE_DRAGGING || mState == STATE_SETTLING) {
            ViewCompat.offsetTopAndBottom(child, savedTop - child.getTop());
        } else if (mState == STATE_ANCHORED) {
            ViewCompat.offsetTopAndBottom(child, mAnchorOffset);
        }
        if (mViewDragHelper == null) {
            mViewDragHelper = ViewDragHelper.create(parent, mDragCallback);
        }
        mViewRef = new WeakReference<>(child);
        mNestedScrollingChildRef = new WeakReference<>(findScrollingChild(child));
        // add missing views to the layout
        ViewGroup nestedScrolling = (ViewGroup) mNestedScrollingChildRef.get();
        if (nestedScrolling.getChildCount() == 0) {
            nestedScrolling.addView(bottomsheet);
        }
        return true;
    }

    @Override
    public boolean onInterceptTouchEvent(CoordinatorLayout parent, V child, MotionEvent event) {
        if (!child.isShown()) {
            return false;
        }

        // set anchor flag on first pass up
        mShouldAnchor = mState == STATE_COLLAPSED;

        int action = MotionEventCompat.getActionMasked(event);
        // Record the velocity
        if (action == MotionEvent.ACTION_DOWN) {
            reset();
        }
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
        mVelocityTracker.addMovement(event);
        switch (action) {
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mTouchingScrollingChild = false;
                mActivePointerId = MotionEvent.INVALID_POINTER_ID;
                // Reset the ignore flag
                if (mIgnoreEvents) {
                    mIgnoreEvents = false;
                    return false;
                }
                break;
            case MotionEvent.ACTION_DOWN:
                int initialX = (int) event.getX();
                mInitialY = (int) event.getY();
                View scroll = mNestedScrollingChildRef.get();
                if (scroll != null && parent.isPointInChildBounds(scroll, initialX, mInitialY)) {
                    mActivePointerId = event.getPointerId(event.getActionIndex());
                    mTouchingScrollingChild = true;
                }
                mIgnoreEvents = mActivePointerId == MotionEvent.INVALID_POINTER_ID &&
                        !parent.isPointInChildBounds(child, initialX, mInitialY);
                break;
        }
        if (!mIgnoreEvents && mViewDragHelper.shouldInterceptTouchEvent(event)) {
            return true;
        }
        // We have to handle cases that the ViewDragHelper does not capture the bottom sheet because
        // it is not the top most view of its parent. This is not necessary when the touch event is
        // happening over the scrolling content as nested scrolling logic handles that case.
        View scroll = mNestedScrollingChildRef.get();
        return action == MotionEvent.ACTION_MOVE && scroll != null &&
                !mIgnoreEvents && mState != STATE_DRAGGING &&
                !parent.isPointInChildBounds(scroll, (int) event.getX(), (int) event.getY()) &&
                Math.abs(mInitialY - event.getY()) > mViewDragHelper.getTouchSlop();
    }

    @Override
    public boolean onTouchEvent(CoordinatorLayout parent, V child, MotionEvent event) {
        if (!child.isShown()) {
            return false;
        }
        int action = MotionEventCompat.getActionMasked(event);
        if (mState == STATE_DRAGGING && action == MotionEvent.ACTION_DOWN) {
            return true;
        }
        mViewDragHelper.processTouchEvent(event);
        // Record the velocity
        if (action == MotionEvent.ACTION_DOWN) {
            reset();
        }
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
        mVelocityTracker.addMovement(event);
        // The ViewDragHelper tries to capture only the top-most View. We have to explicitly tell it
        // to capture the bottom sheet in case it is not captured and the touch slop is passed.
        if (action == MotionEvent.ACTION_MOVE && !mIgnoreEvents) {
            if (Math.abs(mInitialY - event.getY()) > mViewDragHelper.getTouchSlop()) {
                mViewDragHelper.captureChildView(child, event.getPointerId(event.getActionIndex()));
            }
        }
        return !mIgnoreEvents;
    }

    @Override
    public boolean onStartNestedScroll(CoordinatorLayout coordinatorLayout, V child,
                                       View directTargetChild, View target, int nestedScrollAxes) {
        mLastNestedScrollDy = 0;
        mNestedScrolled = false;
        return (nestedScrollAxes & ViewCompat.SCROLL_AXIS_VERTICAL) != 0;
    }

    @Override
    public void onNestedPreScroll(CoordinatorLayout coordinatorLayout, V child, View target, int dx,
                                  int dy, int[] consumed) {
        View scrollingChild = mNestedScrollingChildRef.get();
        if (target != scrollingChild) {
            return;
        }
        int currentTop = child.getTop();
        int newTop = currentTop - dy;
        if (dy > 0) { // Upward
            if (newTop < mAnchorOffset && mShouldAnchor) {
                consumed[1] = currentTop - mAnchorOffset;
                ViewCompat.offsetTopAndBottom(child, -consumed[1]);
                setStateInternal(STATE_ANCHORED);
            } else if (newTop < mMinOffset) {
                consumed[1] = currentTop - mMinOffset;
                ViewCompat.offsetTopAndBottom(child, -consumed[1]);
                setStateInternal(STATE_EXPANDED);
            } else {
                consumed[1] = dy;
                ViewCompat.offsetTopAndBottom(child, -dy);
                setStateInternal(STATE_DRAGGING);
            }
        } else if (dy < 0) { // Downward
            if (!ViewCompat.canScrollVertically(target, -1)) {
                if (newTop <= mMaxOffset || mHideable) {
                    consumed[1] = dy;
                    ViewCompat.offsetTopAndBottom(child, -dy);
                    setStateInternal(STATE_DRAGGING);
                } else {
                    consumed[1] = currentTop - mMaxOffset;
                    ViewCompat.offsetTopAndBottom(child, -consumed[1]);
                    setStateInternal(STATE_COLLAPSED);
                }
            }
        }
        dispatchOnSlide(child.getTop());
        mLastNestedScrollDy = dy;
        mNestedScrolled = true;
    }

    @Override
    public void onStopNestedScroll(CoordinatorLayout coordinatorLayout, V child, View target) {
        if (child.getTop() == mMinOffset) {
            setStateInternal(STATE_EXPANDED);
            return;
        }
        if (target != mNestedScrollingChildRef.get() || !mNestedScrolled) {
            return;
        }
        int top;
        int targetState;
        if (mLastNestedScrollDy > 0) {
            int currentTop = child.getTop();
            if (currentTop > mAnchorOffset || mShouldAnchor) {
                top = mAnchorOffset;
                targetState = STATE_ANCHORED;
            } else {
                top = mMinOffset;
                targetState = STATE_EXPANDED;
            }
        } else if (mHideable && shouldHide(child, getYVelocity())) {
            top = mParentHeight;
            targetState = STATE_HIDDEN;
        } else if (mLastNestedScrollDy == 0) {
            int currentTop = child.getTop();
            if (Math.abs(currentTop - mMinOffset) < Math.abs(currentTop - mMaxOffset)) {
                top = mMinOffset;
                targetState = STATE_EXPANDED;
            } else {
                top = mMaxOffset;
                targetState = STATE_COLLAPSED;
            }
        } else {
            int currentTop = child.getTop();
            if (currentTop > mAnchorOffset) {
                top = mMaxOffset;
                targetState = STATE_COLLAPSED;
            } else {
                top = mAnchorOffset;
                targetState = STATE_ANCHORED;
            }
        }
        if (mViewDragHelper.smoothSlideViewTo(child, child.getLeft(), top)) {
            setStateInternal(STATE_SETTLING);
            ViewCompat.postOnAnimation(child, new SettleRunnable(child, targetState));
        } else {
            setStateInternal(targetState);
        }
        mNestedScrolled = false;
    }

    @Override
    public boolean onNestedPreFling(CoordinatorLayout coordinatorLayout, V child, View target,
                                    float velocityX, float velocityY) {
        return target == mNestedScrollingChildRef.get() &&
                (mState != STATE_EXPANDED ||
                        super.onNestedPreFling(coordinatorLayout, child, target,
                                velocityX, velocityY));
    }

    /**
     * Sets the height of the bottom sheet when it is collapsed.
     *
     * @param peekHeight The height of the collapsed bottom sheet in pixels.
     * @attr ref android.support.design.R.styleable#BottomSheetBehavior_Layout_behavior_peekHeight
     */
    public final void setPeekHeight(int peekHeight) {
        mPeekHeight = Math.max(0, peekHeight);
        mMaxOffset = mParentHeight - peekHeight;
    }

    /**
     * Gets the height of the bottom sheet when it is collapsed.
     *
     * @return The height of the collapsed bottom sheet.
     * @attr ref android.support.design.R.styleable#BottomSheetBehavior_Layout_behavior_peekHeight
     */
    public final int getPeekHeight() {
        return mPeekHeight;
    }

    /**
     * Sets the height of the bottom sheet when it is anchored.
     *
     * @param anchorOffset The offset of the anchored bottom sheet from the top of its parent in pixels.
     * @attr ref R.styleable#BottomSheetBehavior_Layout_behavior_anchorOffset
     */
    public final void setAnchorHeight(int anchorOffset) {
        mAnchorOffset = Math.max(0, anchorOffset);
    }

    /**
     * Gets the height of the bottom sheet when it is anchored.
     *
     * @return The offset of the anchored bottom sheet from the top of its parent.
     * @attr ref R.styleable#BottomSheetBehavior_Layout_behavior_anchorOffset
     */
    public final int getAnchorOffset() {
        return mAnchorOffset;
    }

    public void setCollapsedColor(int collapsedColor) {
        this.mCollapsedColor = collapsedColor;
    }

    public int getCollapsedColor() {
        return mCollapsedColor;
    }

    public void setAnchorColor(int anchorColor) {
        this.mAnchorColor = anchorColor;
    }

    public int getAnchorColor() {
        return mAnchorColor;
    }

    /**
     * Sets whether this bottom sheet can hide when it is swiped down.
     *
     * @param hideable {@code true} to make this bottom sheet hideable.
     * @attr ref R.styleable#BottomSheetBehavior_Layout_behavior_hideable
     */
    public void setHideable(boolean hideable) {
        mHideable = hideable;
    }

    /**
     * Gets whether this bottom sheet can hide when it is swiped down.
     *
     * @return {@code true} if this bottom sheet can hide.
     * @attr ref R.styleable#BottomSheetBehavior_Layout_behavior_hideable
     */
    public boolean isHideable() {
        return mHideable;
    }

    /**
     * Sets whether this bottom sheet should skip the collapsed state when it is being hidden
     * after it is expanded once. Setting this to true has no effect unless the sheet is hideable.
     *
     * @param skipCollapsed True if the bottom sheet should skip the collapsed state.
     * @attr ref R.styleable#BottomSheetBehavior_Layout_behavior_skipCollapsed
     */
    public void setSkipCollapsed(boolean skipCollapsed) {
        mSkipCollapsed = skipCollapsed;
    }

    /**
     * Sets whether this bottom sheet should skip the collapsed state when it is being hidden
     * after it is expanded once.
     *
     * @return Whether the bottom sheet should skip the collapsed state.
     * @attr ref R.styleable#BottomSheetBehavior_Layout_behavior_skipCollapsed
     */
    public boolean getSkipCollapsed() {
        return mSkipCollapsed;
    }

    public void setParallax(View view) {
        this.parallax = view;
    }

    public View getParallax() { return parallax; }

    /**
     * Sets a callback to be notified of bottom sheet events.
     *
     * @param callback The callback to notify when bottom sheet events occur.
     */
    public void setSheetCallback(SheetCallback callback) {
        mCallback = callback;
    }

    /**
     * Sets the state of the bottom sheet. The bottom sheet will transition to that state with
     * animation.
     *
     * @param state One of {@link #STATE_COLLAPSED}, {@link #STATE_EXPANDED},
     *              {@link #STATE_ANCHORED} or {@link #STATE_HIDDEN}.
     */
    public final void setState(@State int state) {
        if (state == mState) {
            return;
        }
        if (mViewRef == null) {
            // The view is not laid out yet; modify mState and let onLayoutChild handle it later
            if (state == STATE_COLLAPSED || state == STATE_EXPANDED ||
                    (mHideable && state == STATE_HIDDEN)) {
                mState = state;
            }
            return;
        }
        V child = mViewRef.get();
        if (child == null) {
            return;
        }
        int top;
        if (state == STATE_COLLAPSED) {
            top = mMaxOffset;
        } else if (state == STATE_EXPANDED) {
            top = mMinOffset;
        } else if (mHideable && state == STATE_HIDDEN) {
            top = mParentHeight;
        } else if (state == STATE_ANCHORED) {
            top = mAnchorOffset;
        } else {
            throw new IllegalArgumentException("Illegal state argument: " + state);
        }
        setStateInternal(STATE_SETTLING);
        if (mViewDragHelper.smoothSlideViewTo(child, child.getLeft(), top)) {
            ViewCompat.postOnAnimation(child, new SettleRunnable(child, state));
        }
    }

    /**
     * Gets the current state of the bottom sheet.
     *
     * @return One of {@link #STATE_EXPANDED}, {@link #STATE_COLLAPSED}, {@link #STATE_DRAGGING},
     * {@link #STATE_ANCHORED} and {@link #STATE_SETTLING}.
     */
    @State
    public final int getState() {
        return mState;
    }

    private void setStateInternal(@State int state) {
        if (mState == state) {
            return;
        }
        mState = state;
        // determine visibility of parallax
        if (parallax != null) {
            if (mState == STATE_COLLAPSED || mState == STATE_HIDDEN) {
                parallax.setVisibility(View.GONE);
            } else {
                parallax.setVisibility(View.VISIBLE);
            }
        }
        if (state == STATE_COLLAPSED) {
            updateHeaderColor(mCollapsedColor);
        } else if (mViewRef.get().getTop() < mMaxOffset  && !stateFlag) {
            updateHeaderColor(mAnchorColor);
        }
        stateFlag = false;
        View sheet = mViewRef.get();
        if (sheet != null && mCallback != null) {
            mCallback.onStateChanged(sheet, state);
        }
    }

    private void reset() {
        mActivePointerId = ViewDragHelper.INVALID_POINTER;
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }
    }

    private boolean shouldHide(View child, float yvel) {
        if (mSkipCollapsed) {
            return true;
        }
        if (child.getTop() < mMaxOffset) {
            // It should not hide, but collapse.
            return false;
        }
        final float newTop = child.getTop() + yvel * HIDE_FRICTION;
        return Math.abs(newTop - mMaxOffset) / (float) mPeekHeight > HIDE_THRESHOLD;
    }

    private View findScrollingChild(View view) {
        if (view instanceof NestedScrollingChild) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0, count = group.getChildCount(); i < count; i++) {
                View scrollingChild = findScrollingChild(group.getChildAt(i));
                if (scrollingChild != null) {
                    return scrollingChild;
                }
            }
        }
        return null;
    }

    private float getYVelocity() {
        mVelocityTracker.computeCurrentVelocity(1000, mMaximumVelocity);
        return VelocityTrackerCompat.getYVelocity(mVelocityTracker, mActivePointerId);
    }

    private final ViewDragHelper.Callback mDragCallback = new ViewDragHelper.Callback() {

        @Override
        public boolean tryCaptureView(View child, int pointerId) {
            if (mState == STATE_DRAGGING) {
                return false;
            }
            if (mTouchingScrollingChild) {
                return false;
            }
            if (mState == STATE_EXPANDED && mActivePointerId == pointerId) {
                View scroll = mNestedScrollingChildRef.get();
                if (scroll != null && ViewCompat.canScrollVertically(scroll, -1)) {
                    // Let the content scroll up
                    return false;
                }
            }
            return mViewRef != null && mViewRef.get() == child;
        }

        @Override
        public void onViewPositionChanged(View changedView, int left, int top, int dx, int dy) {
            dispatchOnSlide(top);
        }

        @Override
        public void onViewDragStateChanged(int state) {
            if (state == ViewDragHelper.STATE_DRAGGING) {
                setStateInternal(STATE_DRAGGING);
            }
        }

        @Override
        public void onViewReleased(View releasedChild, float xvel, float yvel) {
            int top;
            @State int targetState;
            if (yvel < 0) { // Moving up
                top = mMinOffset;
                targetState = STATE_EXPANDED;
            } else if (mHideable && shouldHide(releasedChild, yvel)) {
                top = mParentHeight;
                targetState = STATE_HIDDEN;
            } else if (yvel == 0.f) {
                int currentTop = releasedChild.getTop();
                if (Math.abs(currentTop - mMinOffset) < Math.abs(currentTop - mMaxOffset)) {
                    top = mMinOffset;
                    targetState = STATE_EXPANDED;
                } else {
                    top = mMaxOffset;
                    targetState = STATE_COLLAPSED;
                }
            } else {
                top = mMaxOffset;
                targetState = STATE_COLLAPSED;
            }
            if (mViewDragHelper.settleCapturedViewAt(releasedChild.getLeft(), top)) {
                setStateInternal(STATE_SETTLING);
                ViewCompat.postOnAnimation(releasedChild,
                        new SettleRunnable(releasedChild, targetState));
            } else {
                setStateInternal(targetState);
            }
        }

        @Override
        public int clampViewPositionVertical(View child, int top, int dy) {
            return constrain(top, mMinOffset, mHideable ? mParentHeight : mMaxOffset);
        }

        private int constrain(int amount, int low, int high) {
            return amount < low ? low : (amount > high ? high : amount);
        }

        @Override
        public int clampViewPositionHorizontal(View child, int left, int dx) {
            return child.getLeft();
        }

        @Override
        public int getViewVerticalDragRange(View child) {
            if (mHideable) {
                return mParentHeight - mMinOffset;
            } else {
                return mMaxOffset - mMinOffset;
            }
        }
    };

    private void dispatchOnSlide(int top) {
        View sheet = mViewRef.get();
        // prevent the sheet from moving past the anchor point on the first pass upwards
        if (mShouldAnchor && top < mAnchorOffset) {
            sheet.setTop(mAnchorOffset);
        }

        float slideOffset;
        if (top > mMaxOffset) {
            slideOffset = (float) (mMaxOffset - top) / mPeekHeight;
        } else {
            slideOffset = (float) (mMaxOffset - top) / ((mMaxOffset - mMinOffset));
        }

        // move the parallax with the sheet and update colors
        if (parallax != null) {
            int height = parallax.getHeight();
            float y  = parallax.getY();
            if (slideOffset <= 0) {
                updateHeaderColor(mCollapsedColor);
                parallax.setVisibility(View.GONE);
            } else if (mAnchorOffset >= top && y <= mAnchorOffset - height && (y > 0 || top >= height)) {
                parallax.setY(top - height);
            } else if (slideOffset > 0 && (top >= mAnchorOffset || y > mAnchorOffset - height)) {
                // math for translating parallax relative to bottom sheet
                float init = mAnchorOffset - height;
                float travelDistance = mMaxOffset - init;
                float percentCovered = (top - mAnchorOffset) / (float) (mMaxOffset - mAnchorOffset);
                parallax.setY(init + travelDistance * percentCovered);
            }
        }
        if (sheet != null && mCallback != null) {
            mCallback.onSlide(sheet, slideOffset);
        }
    }

    private class SettleRunnable implements Runnable {

        private final View mView;

        @State
        private final int mTargetState;

        SettleRunnable(View view, @State int targetState) {
            mView = view;
            mTargetState = targetState;
        }

        @Override
        public void run() {
            if (mViewDragHelper != null && mViewDragHelper.continueSettling(true)) {
                ViewCompat.postOnAnimation(mView, this);
            } else {
                setStateInternal(mTargetState);
            }
        }
    }

    protected static class SavedState extends AbsSavedState {
        @State
        final int state;

        public SavedState(Parcel source) {
            this(source, null);
        }

        public SavedState(Parcel source, ClassLoader loader) {
            super(source, loader);
            //noinspection ResourceType
            state = source.readInt();
        }

        public SavedState(Parcelable superState, @State int state) {
            super(superState);
            this.state = state;
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            super.writeToParcel(out, flags);
            out.writeInt(state);
        }

        public static final Creator<SavedState> CREATOR = ParcelableCompat.newCreator(
                new ParcelableCompatCreatorCallbacks<SavedState>() {
                    @Override
                    public SavedState createFromParcel(Parcel in, ClassLoader loader) {
                        return new SavedState(in, loader);
                    }

                    @Override
                    public SavedState[] newArray(int size) {
                        return new SavedState[size];
                    }
                });
    }

    private ValueAnimator colorAnimation;

    private void updateHeaderColor(int color) {
        if (colorAnimation != null && (colorAnimation.isRunning() || colorAnimation.isStarted()) &&
                mCurrentColor != color) {
            colorAnimation.cancel();
        }
        if (colorAnimation != null && colorAnimation.isRunning() || mCurrentColor == color)
            return;
        colorAnimation = ValueAnimator.ofObject(
                new ArgbEvaluator(), mCurrentColor, color).setDuration(200);
        mCurrentColor = color;
        colorAnimation.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator valueAnimator) {
                headerLayout.setBackgroundColor((int) valueAnimator.getAnimatedValue());
            }
        });
        colorAnimation.start();
    }

    /**
     * A utility function to get the {@link SheetBehavior} associated with the {@code view}.
     *
     * @param view The {@link View} with {@link SheetBehavior}.
     * @return The {@link SheetBehavior} associated with the {@code view}.
     */
    @SuppressWarnings("unchecked")
    public static <V extends View> SheetBehavior<V> from(V view) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (!(params instanceof CoordinatorLayout.LayoutParams)) {
            throw new IllegalArgumentException("The view is not a child of CoordinatorLayout");
        }
        CoordinatorLayout.Behavior behavior = ((CoordinatorLayout.LayoutParams) params)
                .getBehavior();
        if (!(behavior instanceof SheetBehavior)) {
            throw new IllegalArgumentException(
                    "The view is not associated with SheetBehavior");
        }
        return (SheetBehavior<V>) behavior;
    }
}
