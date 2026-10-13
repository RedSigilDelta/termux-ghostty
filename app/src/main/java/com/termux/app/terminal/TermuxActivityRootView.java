package com.termux.app.terminal;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.view.WindowInsets;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.WindowInsetsCompat;

import com.termux.app.TermuxActivity;
import com.termux.shared.logger.Logger;

/**
 * Applies a residual IME overlap workaround when {@code adjustResize} still leaves the bottom of
 * the terminal UI covered. The correction is based on actual on-screen overlap and uses padding on
 * the root view so it stays stable in freeform and floating windows.
 */
public class TermuxActivityRootView extends LinearLayout {

    private static final String LOG_TAG = "TermuxActivityRootView";

    private final Rect mVisibleWindowRect = new Rect();
    private final Rect mBottomSpaceViewRect = new Rect();
    private final int[] mBottomSpaceViewLocation = new int[2];
    private final Runnable mApplyTerminalMarginAdjustmentRunnable = this::applyTerminalMarginAdjustment;

    private TermuxActivity mActivity;
    private boolean mRootViewLoggingEnabled;
    private boolean mTerminalMarginAdjustmentEnabled;
    private boolean mImeVisible;
    private boolean mTerminalMarginAdjustmentUpdatePosted;
    private int mAppliedBottomCorrection;
    private int mBasePaddingLeft;
    private int mBasePaddingTop;
    private int mBasePaddingRight;
    private int mBasePaddingBottom;
    private boolean mRealImeInsetsReceived = false;

    /**
     * True between fabricating a remembered keyboard height and reverting it.
     *
     * <p>The pre-apply is a hypothesis for the first frame after the window becomes visible, not a
     * layout state: the fabricated inset resizes the window, so it must be withdrawn as soon as a
     * real inset arrives, or the UI stays sized for a keyboard that never opened.
     */
    private boolean mPreAppliedKeyboardHeightPending = false;
    private final Runnable mRevertPreAppliedKeyboardHeightRunnable = this::revertPreAppliedKeyboardHeight;

    public TermuxActivityRootView(Context context) {
        super(context);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        resetImeHypothesis();
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(mRevertPreAppliedKeyboardHeightRunnable);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == View.VISIBLE) {
            resetImeHypothesis();
        }
    }

    @Override
    protected void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        resetImeHypothesis();
    }

    /** Drops the "no real insets yet" state so the pre-apply can be tried again. */
    private void resetImeHypothesis() {
        mRealImeInsetsReceived = false;
        mPreAppliedKeyboardHeightPending = false;
        removeCallbacks(mRevertPreAppliedKeyboardHeightRunnable);
    }

    /**
     * Withdraws a pre-applied keyboard height that no real inset confirmed.
     *
     * <p>Re-dispatching insets is what produces the next real values, and those then win.
     */
    private void revertPreAppliedKeyboardHeight() {
        if (!mPreAppliedKeyboardHeightPending) return;
        requestApplyInsets();
    }

    public TermuxActivityRootView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public TermuxActivityRootView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setActivity(TermuxActivity activity) {
        mActivity = activity;
    }

    public void setIsRootViewLoggingEnabled(boolean value) {
        mRootViewLoggingEnabled = value;
    }

    public void setTerminalMarginAdjustmentEnabled(boolean enabled) {
        if (mTerminalMarginAdjustmentEnabled == enabled) {
            if (enabled) requestTerminalMarginAdjustmentUpdate();
            return;
        }

        mTerminalMarginAdjustmentEnabled = enabled;

        if (!enabled) {
            removeCallbacks(mApplyTerminalMarginAdjustmentRunnable);
            mTerminalMarginAdjustmentUpdatePosted = false;
            setAppliedBottomCorrection(0);
            return;
        }

        requestApplyInsets();
        requestTerminalMarginAdjustmentUpdate();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        if (!changed) return;
        if (!mTerminalMarginAdjustmentEnabled) return;
        if (!mImeVisible && mAppliedBottomCorrection == 0) return;

        requestTerminalMarginAdjustmentUpdate();
    }

    private void onWindowInsetsChanged(WindowInsets insets) {
        WindowInsetsCompat windowInsets = WindowInsetsCompat.toWindowInsetsCompat(insets);
        int imeBottomInset = windowInsets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
        int systemBarsBottomInset = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;

        mImeVisible = windowInsets.isVisible(WindowInsetsCompat.Type.ime()) || imeBottomInset > systemBarsBottomInset;
        updateBasePadding();

        if (mRootViewLoggingEnabled) {
            Logger.logVerbose(LOG_TAG,
                "onWindowInsetsChanged: imeVisible=" + mImeVisible +
                    ", imeBottomInset=" + imeBottomInset +
                    ", systemBarsBottomInset=" + systemBarsBottomInset +
                    ", basePaddingBottom=" + mBasePaddingBottom +
                    ", appliedBottomCorrection=" + mAppliedBottomCorrection);
        }

        if (!mTerminalMarginAdjustmentEnabled) {
            if (mAppliedBottomCorrection != 0) setAppliedBottomCorrection(0);
            return;
        }

        requestTerminalMarginAdjustmentUpdate();
    }

    private void updateBasePadding() {
        mBasePaddingLeft = getPaddingLeft();
        mBasePaddingTop = getPaddingTop();
        mBasePaddingRight = getPaddingRight();
        mBasePaddingBottom = Math.max(0, getPaddingBottom() - mAppliedBottomCorrection);
    }

    private void requestTerminalMarginAdjustmentUpdate() {
        if (!mTerminalMarginAdjustmentEnabled) return;
        if (mTerminalMarginAdjustmentUpdatePosted) return;

        mTerminalMarginAdjustmentUpdatePosted = true;
        post(mApplyTerminalMarginAdjustmentRunnable);
    }

    private void applyTerminalMarginAdjustment() {
        mTerminalMarginAdjustmentUpdatePosted = false;

        int desiredBottomCorrection = calculateDesiredBottomCorrection();
        if (mRootViewLoggingEnabled) {
            Logger.logVerbose(LOG_TAG,
                "applyTerminalMarginAdjustment: imeVisible=" + mImeVisible +
                    ", current=" + mAppliedBottomCorrection +
                    ", desired=" + desiredBottomCorrection);
        }

        setAppliedBottomCorrection(desiredBottomCorrection);
    }

    private int calculateDesiredBottomCorrection() {
        if (!mImeVisible) return 0;
        if (mActivity == null || !mActivity.isVisible()) return 0;

        View bottomSpaceView = mActivity.getTermuxActivityBottomSpaceView();
        if (bottomSpaceView == null || !bottomSpaceView.isShown()) return 0;

        bottomSpaceView.getWindowVisibleDisplayFrame(mVisibleWindowRect);
        bottomSpaceView.getLocationOnScreen(mBottomSpaceViewLocation);
        mBottomSpaceViewRect.set(
            mBottomSpaceViewLocation[0],
            mBottomSpaceViewLocation[1],
            mBottomSpaceViewLocation[0] + bottomSpaceView.getWidth(),
            mBottomSpaceViewLocation[1] + bottomSpaceView.getHeight());

        int overlap = mBottomSpaceViewRect.bottom - mVisibleWindowRect.bottom;
        int desiredBottomCorrection = Math.max(0, mAppliedBottomCorrection + overlap);

        if (mRootViewLoggingEnabled) {
            Logger.logVerbose(LOG_TAG,
                "calculateDesiredBottomCorrection: visibleWindowRect=" + mVisibleWindowRect +
                    ", bottomSpaceViewRect=" + mBottomSpaceViewRect +
                    ", overlap=" + overlap +
                    ", current=" + mAppliedBottomCorrection +
                    ", desired=" + desiredBottomCorrection);
        }

        return desiredBottomCorrection;
    }

    private void setAppliedBottomCorrection(int bottomCorrection) {
        if (bottomCorrection == mAppliedBottomCorrection) return;

        if (mRootViewLoggingEnabled) {
            Logger.logVerbose(LOG_TAG,
                "setAppliedBottomCorrection: current=" + mAppliedBottomCorrection +
                    ", new=" + bottomCorrection +
                    ", basePaddingBottom=" + mBasePaddingBottom);
        }

        mAppliedBottomCorrection = bottomCorrection;
        setPadding(mBasePaddingLeft, mBasePaddingTop, mBasePaddingRight, mBasePaddingBottom + bottomCorrection);
    }

    public WindowInsets adjustWindowInsets(WindowInsets insets) {
        if (mActivity == null) return insets;

        WindowInsetsCompat windowInsets = WindowInsetsCompat.toWindowInsetsCompat(insets);
        int imeBottomInset = windowInsets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
        int systemBarsBottomInset = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;

        boolean isRealImeVisible = windowInsets.isVisible(WindowInsetsCompat.Type.ime()) || imeBottomInset > systemBarsBottomInset;

        android.util.Log.w("TermuxActivityRootView", "adjustWindowInsets: isRealImeVisible=" + isRealImeVisible 
            + ", mRealImeInsetsReceived=" + mRealImeInsetsReceived 
            + ", imeBottomInset=" + imeBottomInset 
            + ", systemBarsBottomInset=" + systemBarsBottomInset);

        if (isRealImeVisible) {
            mRealImeInsetsReceived = true;
            mPreAppliedKeyboardHeightPending = false;
            removeCallbacks(mRevertPreAppliedKeyboardHeightRunnable);
            int keyboardHeight = Math.max(0, imeBottomInset - systemBarsBottomInset);
            if (keyboardHeight > 0) {
                int orientation = getResources().getConfiguration().orientation;
                if (orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) {
                    mActivity.getPreferences().setLastSoftKeyboardHeightPortrait(keyboardHeight);
                } else if (orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                    mActivity.getPreferences().setLastSoftKeyboardHeightLandscape(keyboardHeight);
                }
            }
        }

        // Second phase of the hypothesis: the first real inset dispatch settles the layout, so the
        // fabricated height is dropped whether or not the keyboard turned out to be visible.
        if (mPreAppliedKeyboardHeightPending) {
            mPreAppliedKeyboardHeightPending = false;
            removeCallbacks(mRevertPreAppliedKeyboardHeightRunnable);
            Logger.logWarn(LOG_TAG, "Withdrawing pre-applied keyboard height: imeVisible=" + isRealImeVisible
                + ", imeBottomInset=" + imeBottomInset);
            return insets;
        }

        if (!mRealImeInsetsReceived
                && mActivity.getSettings().getRememberSoftKeyboardState()
                && com.termux.shared.termux.settings.preferences.TermuxPreferenceConstants.TERMUX_APP.VALUE_LAST_SOFT_KEYBOARD_STATE_VISIBLE.equals(mActivity.getPreferences().getLastSoftKeyboardState())) {

            int lastKeyboardHeight = 0;
            int orientation = getResources().getConfiguration().orientation;
            if (orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) {
                lastKeyboardHeight = mActivity.getPreferences().getLastSoftKeyboardHeightPortrait();
            } else if (orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                lastKeyboardHeight = mActivity.getPreferences().getLastSoftKeyboardHeightLandscape();
            }

            if (lastKeyboardHeight > 0) {
                int newImeBottom = systemBarsBottomInset + lastKeyboardHeight;
                android.util.Log.w("TermuxActivityRootView", "Pre-applying last known keyboard height in insets: " + lastKeyboardHeight + " for orientation " + orientation);
                WindowInsetsCompat.Builder builder = new WindowInsetsCompat.Builder(windowInsets);
                builder.setInsets(WindowInsetsCompat.Type.ime(), Insets.of(
                    windowInsets.getInsets(WindowInsetsCompat.Type.ime()).left,
                    windowInsets.getInsets(WindowInsetsCompat.Type.ime()).top,
                    windowInsets.getInsets(WindowInsetsCompat.Type.ime()).right,
                    newImeBottom
                ));
                WindowInsetsCompat modifiedCompat = builder.build();
                WindowInsets modifiedInsets = modifiedCompat.toWindowInsets();
                if (modifiedInsets != null) {
                    // Bound the hypothesis to this dispatch: if no real inset confirms it, the
                    // revert re-dispatches insets so the window shrinks back.
                    mPreAppliedKeyboardHeightPending = true;
                    removeCallbacks(mRevertPreAppliedKeyboardHeightRunnable);
                    post(mRevertPreAppliedKeyboardHeightRunnable);
                    return modifiedInsets;
                }
            }
        }

        return insets;
    }

    public static class WindowInsetsListener implements View.OnApplyWindowInsetsListener {
        @Override
        public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
            if (v instanceof TermuxActivityRootView) {
                insets = ((TermuxActivityRootView) v).adjustWindowInsets(insets);
            }
            WindowInsets appliedInsets = v.onApplyWindowInsets(insets);
            if (v instanceof TermuxActivityRootView) {
                ((TermuxActivityRootView) v).onWindowInsetsChanged(insets);
            }
            return appliedInsets;
        }
    }

}
