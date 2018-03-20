/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.quickstep;

import static com.android.quickstep.QuickScrubController.QUICK_SWITCH_START_DURATION;
import static com.android.quickstep.TouchConsumer.INTERACTION_NORMAL;
import static com.android.quickstep.TouchConsumer.INTERACTION_QUICK_SCRUB;
import static com.android.quickstep.TouchConsumer.INTERACTION_QUICK_SWITCH;
import static com.android.quickstep.TouchConsumer.isInteractionQuick;
import static com.android.systemui.shared.recents.utilities.Utilities
        .postAtFrontOfQueueAsynchronously;
import static com.android.systemui.shared.system.RemoteAnimationTargetCompat.MODE_CLOSING;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.annotation.TargetApi;
import android.app.ActivityManager.RunningTaskInfo;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Matrix;
import android.graphics.Matrix.ScaleToFit;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.support.annotation.UiThread;
import android.support.annotation.WorkerThread;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver.OnDrawListener;

import com.android.launcher3.AbstractFloatingView;
import com.android.launcher3.BaseDraggingActivity;
import com.android.launcher3.DeviceProfile;
import com.android.launcher3.LauncherAppState;
import com.android.launcher3.MainThreadExecutor;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.anim.AnimationSuccessListener;
import com.android.launcher3.anim.AnimatorPlaybackController;
import com.android.launcher3.anim.Interpolators;
import com.android.launcher3.logging.UserEventDispatcher;
import com.android.launcher3.userevent.nano.LauncherLogProto.Action.Direction;
import com.android.launcher3.userevent.nano.LauncherLogProto.Action.Touch;
import com.android.launcher3.userevent.nano.LauncherLogProto.ContainerType;
import com.android.launcher3.util.TraceHelper;
import com.android.quickstep.ActivityControlHelper.ActivityInitListener;
import com.android.quickstep.ActivityControlHelper.LayoutListener;
import com.android.quickstep.TouchConsumer.InteractionType;
import com.android.quickstep.util.SysuiEventLogger;
import com.android.quickstep.views.RecentsView;
import com.android.quickstep.views.TaskView;
import com.android.systemui.shared.recents.model.ThumbnailData;
import com.android.systemui.shared.recents.utilities.RectFEvaluator;
import com.android.systemui.shared.system.InputConsumerController;
import com.android.systemui.shared.system.LatencyTrackerCompat;
import com.android.systemui.shared.system.RecentsAnimationControllerCompat;
import com.android.systemui.shared.system.RemoteAnimationTargetCompat;
import com.android.systemui.shared.system.TransactionCompat;
import com.android.systemui.shared.system.WindowManagerWrapper;

import java.util.StringJoiner;

@TargetApi(Build.VERSION_CODES.O)
public class WindowTransformSwipeHandler<T extends BaseDraggingActivity> {
    private static final String TAG = WindowTransformSwipeHandler.class.getSimpleName();
    private static final boolean DEBUG_STATES = false;

    // Launcher UI related states
    private static final int STATE_LAUNCHER_PRESENT = 1 << 0;
    private static final int STATE_LAUNCHER_STARTED = 1 << 1;
    private static final int STATE_LAUNCHER_DRAWN = 1 << 2;
    private static final int STATE_ACTIVITY_MULTIPLIER_COMPLETE = 1 << 3;

    // Internal initialization states
    private static final int STATE_APP_CONTROLLER_RECEIVED = 1 << 4;

    // Interaction finish states
    private static final int STATE_SCALED_CONTROLLER_RECENTS = 1 << 5;
    private static final int STATE_SCALED_CONTROLLER_APP = 1 << 6;

    private static final int STATE_HANDLER_INVALIDATED = 1 << 7;
    private static final int STATE_GESTURE_STARTED = 1 << 8;
    private static final int STATE_GESTURE_CANCELLED = 1 << 9;

    // States for quick switch/scrub
    private static final int STATE_SWITCH_TO_SCREENSHOT_COMPLETE = 1 << 10;
    private static final int STATE_QUICK_SWITCH = 1 << 11;
    private static final int STATE_QUICK_SCRUB_START = 1 << 12;
    private static final int STATE_QUICK_SCRUB_END = 1 << 13;

    private static final int LAUNCHER_UI_STATES =
            STATE_LAUNCHER_PRESENT | STATE_LAUNCHER_DRAWN | STATE_ACTIVITY_MULTIPLIER_COMPLETE
            | STATE_LAUNCHER_STARTED;

    // For debugging, keep in sync with above states
    private static final String[] STATES = new String[] {
            "STATE_LAUNCHER_PRESENT",
            "STATE_LAUNCHER_STARTED",
            "STATE_LAUNCHER_DRAWN",
            "STATE_ACTIVITY_MULTIPLIER_COMPLETE",
            "STATE_APP_CONTROLLER_RECEIVED",
            "STATE_SCALED_CONTROLLER_RECENTS",
            "STATE_SCALED_CONTROLLER_APP",
            "STATE_HANDLER_INVALIDATED",
            "STATE_GESTURE_STARTED",
            "STATE_GESTURE_CANCELLED",
            "STATE_SWITCH_TO_SCREENSHOT_COMPLETE",
            "STATE_QUICK_SWITCH",
            "STATE_QUICK_SCRUB_START",
            "STATE_QUICK_SCRUB_END"
    };

    private static final long MAX_SWIPE_DURATION = 200;
    private static final long MIN_SWIPE_DURATION = 80;

    private static final float MIN_PROGRESS_FOR_OVERVIEW = 0.5f;

    // The bounds of the source app in device coordinates
    private final Rect mSourceStackBounds = new Rect();
    // The insets of the source app
    private final Rect mSourceInsets = new Rect();
    // The source app bounds with the source insets applied, in the source app window coordinates
    private final RectF mSourceRect = new RectF();
    // The bounds of the task view in launcher window coordinates
    private final RectF mTargetRect = new RectF();
    // Doesn't change after initialized, used as an anchor when changing mTargetRect
    private final RectF mInitialTargetRect = new RectF();
    // The insets to be used for clipping the app window, which can be larger than mSourceInsets
    // if the aspect ratio of the target is smaller than the aspect ratio of the source rect. In
    // app window coordinates.
    private final RectF mSourceWindowClipInsets = new RectF();

    // The bounds of launcher (not including insets) in device coordinates
    private final Rect mHomeStackBounds = new Rect();
    // The clip rect in source app window coordinates
    private final Rect mClipRect = new Rect();
    private final RectFEvaluator mRectFEvaluator = new RectFEvaluator();
    protected Runnable mGestureEndCallback;
    protected boolean mIsGoingToHome;
    private DeviceProfile mDp;
    private int mTransitionDragLength;

    // Shift in the range of [0, 1].
    // 0 => preview snapShot is completely visible, and hotseat is completely translated down
    // 1 => preview snapShot is completely aligned with the recents view and hotseat is completely
    // visible.
    private final AnimatedFloat mCurrentShift = new AnimatedFloat(this::updateFinalShift);

    private final MainThreadExecutor mMainExecutor = new MainThreadExecutor();

    private final Context mContext;
    private final int mRunningTaskId;
    private final ActivityControlHelper<T> mActivityControlHelper;
    private final ActivityInitListener mActivityInitListener;

    private MultiStateCallback mStateCallback;
    private AnimatorPlaybackController mLauncherTransitionController;

    private T mActivity;
    private LayoutListener mLayoutListener;
    private RecentsView mRecentsView;
    private QuickScrubController mQuickScrubController;

    private Runnable mLauncherDrawnCallback;

    private boolean mWasLauncherAlreadyVisible;

    private float mCurrentDisplacement;
    private boolean mGestureStarted;
    private int mLogAction = Touch.SWIPE;

    private @InteractionType int mInteractionType = INTERACTION_NORMAL;

    private InputConsumerController mInputConsumer =
            InputConsumerController.getRecentsAnimationInputConsumer();

    private final RecentsAnimationWrapper mRecentsAnimationWrapper = new RecentsAnimationWrapper();
    private Matrix mTmpMatrix = new Matrix();
    private final long mTouchTimeMs;
    private long mLauncherFrameDrawnTime;

    WindowTransformSwipeHandler(RunningTaskInfo runningTaskInfo, Context context, long touchTimeMs,
            ActivityControlHelper<T> controller) {
        mContext = context;
        mRunningTaskId = runningTaskInfo.id;
        mTouchTimeMs = touchTimeMs;
        mActivityControlHelper = controller;
        mActivityInitListener = mActivityControlHelper
                .createActivityInitListener(this::onActivityInit);

        // Register the input consumer on the UI thread, to ensure that it runs after any pending
        // unregister calls
        mMainExecutor.execute(mInputConsumer::registerInputConsumer);
        initStateCallbacks();
    }

    private void initStateCallbacks() {
        mStateCallback = new MultiStateCallback() {
            @Override
            public void setState(int stateFlag) {
                debugNewState(stateFlag);
                super.setState(stateFlag);
            }
        };

        mStateCallback.addCallback(STATE_LAUNCHER_DRAWN | STATE_GESTURE_STARTED,
                this::initializeLauncherAnimationController);
        mStateCallback.addCallback(STATE_LAUNCHER_PRESENT | STATE_LAUNCHER_DRAWN,
                this::launcherFrameDrawn);
        mStateCallback.addCallback(STATE_LAUNCHER_PRESENT | STATE_GESTURE_STARTED,
                this::notifyGestureStarted);
        mStateCallback.addCallback(STATE_LAUNCHER_PRESENT | STATE_LAUNCHER_STARTED
                        | STATE_GESTURE_CANCELLED,
                this::resetStateForAnimationCancel);

        mStateCallback.addCallback(STATE_LAUNCHER_PRESENT | STATE_APP_CONTROLLER_RECEIVED
                        | STATE_SCALED_CONTROLLER_APP,
                this::resumeLastTask);
        mStateCallback.addCallback(STATE_LAUNCHER_PRESENT | STATE_APP_CONTROLLER_RECEIVED
                        | STATE_ACTIVITY_MULTIPLIER_COMPLETE
                        | STATE_SCALED_CONTROLLER_RECENTS,
                this::switchToScreenshot);
        mStateCallback.addCallback(STATE_LAUNCHER_PRESENT | STATE_APP_CONTROLLER_RECEIVED
                        | STATE_ACTIVITY_MULTIPLIER_COMPLETE
                        | STATE_SCALED_CONTROLLER_RECENTS
                        | STATE_SWITCH_TO_SCREENSHOT_COMPLETE,
                this::setupLauncherUiAfterSwipeUpAnimation);

        mStateCallback.addCallback(STATE_LAUNCHER_PRESENT | STATE_SCALED_CONTROLLER_APP,
                this::reset);

        mStateCallback.addCallback(STATE_HANDLER_INVALIDATED, this::invalidateHandler);
        mStateCallback.addCallback(STATE_LAUNCHER_PRESENT | STATE_HANDLER_INVALIDATED,
                this::invalidateHandlerWithLauncher);

        mStateCallback.addCallback(STATE_LAUNCHER_PRESENT | STATE_QUICK_SWITCH,
                this::onQuickInteractionStart);
        mStateCallback.addCallback(STATE_LAUNCHER_PRESENT | STATE_QUICK_SCRUB_START,
                this::onQuickInteractionStart);

        mStateCallback.addCallback(STATE_LAUNCHER_PRESENT | STATE_SWITCH_TO_SCREENSHOT_COMPLETE
                | STATE_QUICK_SWITCH, this::switchToFinalAppAfterQuickSwitch);
        mStateCallback.addCallback(STATE_LAUNCHER_PRESENT | STATE_SWITCH_TO_SCREENSHOT_COMPLETE
                | STATE_QUICK_SCRUB_END, this::switchToFinalAppAfterQuickScrub);
    }

    private void setStateOnUiThread(int stateFlag) {
        Handler handler = mMainExecutor.getHandler();
        if (Looper.myLooper() == handler.getLooper()) {
            mStateCallback.setState(stateFlag);
        } else {
            postAtFrontOfQueueAsynchronously(handler, () -> mStateCallback.setState(stateFlag));
        }
    }

    private void initTransitionEndpoints(DeviceProfile dp) {
        mDp = dp;
        mSourceRect.set(mSourceInsets.left, mSourceInsets.top,
                mSourceStackBounds.width() - mSourceInsets.right,
                mSourceStackBounds.height() - mSourceInsets.bottom);

        Rect tempRect = new Rect();
        mTransitionDragLength = mActivityControlHelper
                .getSwipeUpDestinationAndLength(dp, mContext, tempRect);

        mTargetRect.set(tempRect);
        mTargetRect.offset(mHomeStackBounds.left - mSourceStackBounds.left,
                mHomeStackBounds.top - mSourceStackBounds.top);
        mInitialTargetRect.set(mTargetRect);

        // Calculate the clip based on the target rect (since the content insets and the
        // launcher insets may differ, so the aspect ratio of the target rect can differ
        // from the source rect. The difference between the target rect (scaled to the
        // source rect) is the amount to clip on each edge.
        RectF scaledTargetRect = new RectF(mTargetRect);
        Utilities.scaleRectFAboutCenter(scaledTargetRect,
                mSourceRect.width() / mTargetRect.width());
        scaledTargetRect.offsetTo(mSourceRect.left, mSourceRect.top);
        mSourceWindowClipInsets.set(
                Math.max(scaledTargetRect.left, 0),
                Math.max(scaledTargetRect.top, 0),
                Math.max(mSourceStackBounds.width() - scaledTargetRect.right, 0),
                Math.max(mSourceStackBounds.height() - scaledTargetRect.bottom, 0));
        mSourceRect.set(scaledTargetRect);
    }

    public int getTransitionLength() {
        return mTransitionDragLength;
    }

    public RectF getTargetRect(Point outWindowSize) {
        outWindowSize.set(mDp.widthPx, mDp.heightPx);
        return mInitialTargetRect;
    }

    private long getFadeInDuration() {
        if (mCurrentShift.getCurrentAnimation() != null) {
            ObjectAnimator anim = mCurrentShift.getCurrentAnimation();
            long theirDuration = anim.getDuration() - anim.getCurrentPlayTime();

            // TODO: Find a better heuristic
            return Math.min(MAX_SWIPE_DURATION, Math.max(theirDuration, MIN_SWIPE_DURATION));
        } else {
            return MAX_SWIPE_DURATION;
        }
    }

    public void initWhenReady() {
        mActivityInitListener.register();
    }

    private boolean onActivityInit(final T activity, Boolean alreadyOnHome) {
        if (mActivity == activity) {
            return true;
        }
        if (mActivity != null) {
            // The launcher may have been recreated as a result of device rotation.
            int oldState = mStateCallback.getState() & ~LAUNCHER_UI_STATES;
            initStateCallbacks();
            mStateCallback.setState(oldState);
            mLayoutListener.setHandler(null);
        }
        mWasLauncherAlreadyVisible = alreadyOnHome;
        mActivity = activity;

        mRecentsView = activity.getOverviewPanel();
        mQuickScrubController = mRecentsView.getQuickScrubController();
        mLayoutListener = mActivityControlHelper.createLayoutListener(mActivity);

        mStateCallback.setState(STATE_LAUNCHER_PRESENT);
        if (alreadyOnHome) {
            onLauncherStart(activity);
        } else {
            activity.setOnStartCallback(this::onLauncherStart);
        }
        return true;
    }

    private void onLauncherStart(final T activity) {
        if (mActivity != activity) {
            return;
        }
        if ((mStateCallback.getState() & STATE_HANDLER_INVALIDATED) != 0) {
            return;
        }

        mStateCallback.setState(STATE_LAUNCHER_STARTED);
        mActivityControlHelper.prepareRecentsUI(mActivity, mWasLauncherAlreadyVisible);
        AbstractFloatingView.closeAllOpenViews(activity, mWasLauncherAlreadyVisible);


        if (mWasLauncherAlreadyVisible) {
            mLauncherTransitionController = mActivityControlHelper
                    .createControllerForVisibleActivity(activity);
            mLauncherTransitionController.dispatchOnStart();
            mLauncherTransitionController.setPlayFraction(mCurrentShift.value);

            mStateCallback.setState(STATE_ACTIVITY_MULTIPLIER_COMPLETE | STATE_LAUNCHER_DRAWN);
        } else {
            TraceHelper.beginSection("WTS-init");
            // TODO: Implement a better animation for fading in
            View rootView = activity.getRootView();
            rootView.setAlpha(0);
            rootView.getViewTreeObserver().addOnDrawListener(new OnDrawListener() {

                @Override
                public void onDraw() {
                    TraceHelper.endSection("WTS-init", "Launcher frame is drawn");
                    rootView.post(() ->
                            rootView.getViewTreeObserver().removeOnDrawListener(this));
                    if (activity != mActivity) {
                        return;
                    }

                    mStateCallback.setState(STATE_LAUNCHER_DRAWN);
                }
            });
        }

        mRecentsView.showTask(mRunningTaskId);
        mRecentsView.setFirstTaskIconScaledDown(true /* isScaledDown */, false /* animate */);
        mLayoutListener.open();
    }

    public void setLauncherOnDrawCallback(Runnable callback) {
        mLauncherDrawnCallback = callback;
    }

    private void launcherFrameDrawn() {
        View rootView = mActivity.getRootView();
        if (rootView.getAlpha() < 1) {
            if (mGestureStarted) {
                final MultiStateCallback callback = mStateCallback;
                rootView.animate().alpha(1)
                        .setDuration(getFadeInDuration())
                        .withEndAction(() -> callback.setState(STATE_ACTIVITY_MULTIPLIER_COMPLETE));
            } else {
                rootView.setAlpha(1);
                mStateCallback.setState(STATE_ACTIVITY_MULTIPLIER_COMPLETE);
            }
        }
        if (mLauncherDrawnCallback != null) {
            mLauncherDrawnCallback.run();
        }
        mLauncherFrameDrawnTime = SystemClock.uptimeMillis();
    }

    private void initializeLauncherAnimationController() {
        mLayoutListener.setHandler(this);
        onLauncherLayoutChanged();

        final long transitionDelay = mLauncherFrameDrawnTime - mTouchTimeMs;
        SysuiEventLogger.writeDummyRecentsTransition(transitionDelay);

        if (LatencyTrackerCompat.isEnabled(mContext)) {
            LatencyTrackerCompat.logToggleRecents((int) transitionDelay);
        }
    }

    public void updateInteractionType(@InteractionType int interactionType) {
        if (mInteractionType != INTERACTION_NORMAL) {
            throw new IllegalArgumentException(
                    "Can't change interaction type from " + mInteractionType);
        }
        if (!isInteractionQuick(interactionType)) {
            throw new IllegalArgumentException(
                    "Can't change interaction type to " + interactionType);
        }
        mInteractionType = interactionType;

        setStateOnUiThread(interactionType == INTERACTION_QUICK_SWITCH
                ? STATE_QUICK_SWITCH : STATE_QUICK_SCRUB_START);

        // Start the window animation without waiting for launcher.
        animateToProgress(1f, QUICK_SWITCH_START_DURATION);
    }

    private void onQuickInteractionStart() {
        mActivityControlHelper.onQuickInteractionStart(mActivity,
                mWasLauncherAlreadyVisible || mGestureStarted);
        mQuickScrubController.onQuickScrubStart(false);
    }

    @WorkerThread
    public void updateDisplacement(float displacement) {
        mCurrentDisplacement = displacement;

        float translation = Utilities.boundToRange(-mCurrentDisplacement, 0, mTransitionDragLength);
        float shift = mTransitionDragLength == 0 ? 0 : translation / mTransitionDragLength;
        mCurrentShift.updateValue(shift);
    }

    /**
     * Called by {@link #mLayoutListener} when launcher layout changes
     */
    public void onLauncherLayoutChanged() {
        initTransitionEndpoints(mActivity.getDeviceProfile());

        if (!mWasLauncherAlreadyVisible) {
            mLauncherTransitionController = mActivityControlHelper
                    .createControllerForHiddenActivity(mActivity, mTransitionDragLength);
            mLauncherTransitionController.setPlayFraction(mCurrentShift.value);
        }
    }

    @WorkerThread
    private void updateFinalShift() {
        float shift = mCurrentShift.value;

        synchronized (mRecentsAnimationWrapper) {
            if (mRecentsAnimationWrapper.controller != null) {
                RectF currentRect;
                synchronized (mTargetRect) {
                    currentRect = mRectFEvaluator.evaluate(shift, mSourceRect, mTargetRect);
                }

                mClipRect.left = (int) (mSourceWindowClipInsets.left * shift);
                mClipRect.top = (int) (mSourceWindowClipInsets.top * shift);
                mClipRect.right = (int)
                        (mSourceStackBounds.width() - (mSourceWindowClipInsets.right * shift));
                mClipRect.bottom = (int)
                        (mSourceStackBounds.height() - (mSourceWindowClipInsets.bottom * shift));

                mTmpMatrix.setRectToRect(mSourceRect, currentRect, ScaleToFit.FILL);

                TransactionCompat transaction = new TransactionCompat();
                for (RemoteAnimationTargetCompat app : mRecentsAnimationWrapper.targets) {
                    if (app.mode == MODE_CLOSING) {
                        transaction.setMatrix(app.leash, mTmpMatrix)
                                .setWindowCrop(app.leash, mClipRect);

                        if (app.isNotInRecents) {
                            transaction.setAlpha(app.leash, 1 - shift);
                        }

                        transaction.show(app.leash);
                    }
                }
                transaction.apply();
            }
        }

        if (mLauncherTransitionController != null) {
            Runnable runOnUi = () -> {
                if (mLauncherTransitionController == null) {
                    return;
                }
                mLauncherTransitionController.setPlayFraction(shift);

                // Make sure the window follows the first task if it moves, e.g. during quick scrub.
                View firstTask = mRecentsView.getPageAt(0);
                int scrollForFirstTask = mRecentsView.getScrollForPage(0);
                int offsetFromFirstTask = (scrollForFirstTask - mRecentsView.getScrollX());
                if (offsetFromFirstTask != 0) {
                    synchronized (mTargetRect) {
                        mTargetRect.set(mInitialTargetRect);
                        Utilities.scaleRectFAboutCenter(mTargetRect, firstTask.getScaleX());
                        float offsetX = offsetFromFirstTask + firstTask.getTranslationX();
                        mTargetRect.offset(offsetX, 0);
                    }
                }
                if (mRecentsAnimationWrapper.controller != null) {

                    // TODO: This logic is spartanic!
                    mRecentsAnimationWrapper.controller.setAnimationTargetsBehindSystemBars(
                            shift < 0.12f);
                }
            };
            if (Looper.getMainLooper() == Looper.myLooper()) {
                runOnUi.run();
            } else {
                // The fling operation completed even before the launcher was drawn
                mMainExecutor.execute(runOnUi);
            }
        }
    }

    public void onRecentsAnimationStart(RecentsAnimationControllerCompat controller,
            RemoteAnimationTargetCompat[] apps, Rect homeContentInsets, Rect minimizedHomeBounds) {
        if (apps != null) {
            // Use the top closing app to determine the insets for the animation
            for (RemoteAnimationTargetCompat target : apps) {
                if (target.mode == MODE_CLOSING) {
                    DeviceProfile dp = LauncherAppState.getIDP(mContext).getDeviceProfile(mContext);
                    if (minimizedHomeBounds != null) {
                        mHomeStackBounds.set(minimizedHomeBounds);
                        dp = dp.getMultiWindowProfile(mContext,
                                new Point(minimizedHomeBounds.width(), minimizedHomeBounds.height()));
                        dp.updateInsets(homeContentInsets);
                    } else {
                        mHomeStackBounds.set(new Rect(0, 0, dp.widthPx, dp.heightPx));
                        // TODO: Workaround for an existing issue where the home content insets are
                        // not valid immediately after rotation, just use the stable insets for now
                        Rect insets = new Rect();
                        WindowManagerWrapper.getInstance().getStableInsets(insets);
                        dp.updateInsets(insets);
                    }

                    // Initialize the start and end animation bounds
                    // TODO: Remove once platform is updated
                    try {
                        mSourceInsets.set(target.getContentInsets());
                    } catch (Error e) {
                        // TODO: Remove once platform is updated, use stable insets as fallback
                        WindowManagerWrapper.getInstance().getStableInsets(mSourceInsets);
                    }
                    mSourceStackBounds.set(target.sourceContainerBounds);

                    initTransitionEndpoints(dp);
                    break;
                }
            }
        }
        mRecentsAnimationWrapper.setController(controller, apps);
        setStateOnUiThread(STATE_APP_CONTROLLER_RECEIVED);
    }

    public void onRecentsAnimationCanceled() {
        mRecentsAnimationWrapper.setController(null, null);
        mActivityInitListener.unregister();
        setStateOnUiThread(STATE_GESTURE_CANCELLED | STATE_HANDLER_INVALIDATED);
    }

    public void onGestureStarted() {
        notifyGestureStarted();
        setStateOnUiThread(STATE_GESTURE_STARTED);
        mGestureStarted = true;
        mRecentsAnimationWrapper.enableInputConsumer();
    }

    /**
     * Notifies the launcher that the swipe gesture has started. This can be called multiple times
     * on both background and UI threads
     */
    private void notifyGestureStarted() {
        final T curActivity = mActivity;
        if (curActivity != null) {
            mActivityControlHelper.onQuickstepGestureStarted(
                    curActivity, mWasLauncherAlreadyVisible);
        }
    }

    @WorkerThread
    public void onGestureEnded(float endVelocity) {
        Resources res = mContext.getResources();
        float flingThreshold = res.getDimension(R.dimen.quickstep_fling_threshold_velocity);
        boolean isFling = Math.abs(endVelocity) > flingThreshold;

        long duration = MAX_SWIPE_DURATION;
        final float endShift;
        if (!isFling) {
            endShift = mCurrentShift.value >= MIN_PROGRESS_FOR_OVERVIEW ? 1 : 0;
            mLogAction = Touch.SWIPE;
        } else {
            endShift = endVelocity < 0 ? 1 : 0;
            float minFlingVelocity = res.getDimension(R.dimen.quickstep_fling_min_velocity);
            if (Math.abs(endVelocity) > minFlingVelocity && mTransitionDragLength > 0) {
                float distanceToTravel = (endShift - mCurrentShift.value) * mTransitionDragLength;

                // we want the page's snap velocity to approximately match the velocity at
                // which the user flings, so we scale the duration by a value near to the
                // derivative of the scroll interpolator at zero, ie. 5.
                duration = 5 * Math.round(1000 * Math.abs(distanceToTravel / endVelocity));
            }
            mLogAction = Touch.FLING;
        }

        animateToProgress(endShift, duration);
    }

    private void doLogGesture(boolean toLauncher) {
        final int direction;
        if (mDp.isVerticalBarLayout()) {
            direction = (mDp.isSeascape() ^ toLauncher) ? Direction.LEFT : Direction.RIGHT;
        } else {
            direction = toLauncher ? Direction.UP : Direction.DOWN;
        }

        int dstContainerType = toLauncher ? ContainerType.TASKSWITCHER : ContainerType.APP;
        UserEventDispatcher.newInstance(mContext, mDp).logStateChangeAction(
                mLogAction, direction,
                ContainerType.NAVBAR, ContainerType.APP,
                dstContainerType,
                0);
    }

    /** Animates to the given progress, where 0 is the current app and 1 is overview. */
    private void animateToProgress(float progress, long duration) {
        mIsGoingToHome = Float.compare(progress, 1) == 0;
        ObjectAnimator anim = mCurrentShift.animateToValue(progress).setDuration(duration);
        anim.setInterpolator(Interpolators.SCROLL);
        anim.addListener(new AnimationSuccessListener() {
            @Override
            public void onAnimationSuccess(Animator animator) {
                setStateOnUiThread(mIsGoingToHome ?
                        STATE_SCALED_CONTROLLER_RECENTS : STATE_SCALED_CONTROLLER_APP);
            }
        });
        anim.start();
    }

    @UiThread
    private void resumeLastTask() {
        mRecentsAnimationWrapper.finish(false /* toHome */, null);
        doLogGesture(false /* toLauncher */);
    }

    public void reset() {
        if (mInteractionType != INTERACTION_QUICK_SCRUB) {
            // Only invalidate the handler if we are not quick scrubbing, otherwise, it will be
            // invalidated after the quick scrub ends
            setStateOnUiThread(STATE_HANDLER_INVALIDATED);
        }
    }

    private void invalidateHandler() {
        mCurrentShift.finishAnimation();

        if (mGestureEndCallback != null) {
            mGestureEndCallback.run();
        }

        mActivityInitListener.unregister();
        mInputConsumer.unregisterInputConsumer();
    }

    private void invalidateHandlerWithLauncher() {
        mLauncherTransitionController = null;
        mLayoutListener.finish();

        mRecentsView.setFirstTaskIconScaledDown(false /* isScaledDown */, false /* animate */);
    }

    private void resetStateForAnimationCancel() {
        boolean wasVisible = mWasLauncherAlreadyVisible || mGestureStarted;
        mActivityControlHelper.onTransitionCancelled(mActivity, wasVisible);
    }

    public void layoutListenerClosed() {
        if (mWasLauncherAlreadyVisible && mLauncherTransitionController != null) {
            mLauncherTransitionController.setPlayFraction(1);
        }
    }

    private void switchToScreenshot() {
        boolean finishTransitionPosted = false;
        final Runnable finishTransitionRunnable = () -> {
            synchronized (mRecentsAnimationWrapper) {
                mRecentsAnimationWrapper.finish(true /* toHome */,
                        () -> setStateOnUiThread(STATE_SWITCH_TO_SCREENSHOT_COMPLETE));
            }
        };
        synchronized (mRecentsAnimationWrapper) {
            if (mRecentsAnimationWrapper.controller != null) {
                TransactionCompat transaction = new TransactionCompat();
                for (RemoteAnimationTargetCompat app : mRecentsAnimationWrapper.targets) {
                    if (app.mode == MODE_CLOSING) {
                        // Update the screenshot of the task
                        ThumbnailData thumbnail =
                                mRecentsAnimationWrapper.controller.screenshotTask(app.taskId);
                        TaskView taskView = mRecentsView.updateThumbnail(app.taskId, thumbnail);
                        if (taskView != null) {
                            // Defer finishing the animation until the next launcher frame with the
                            // new thumbnail
                            mActivityControlHelper.executeOnNextDraw(mActivity, taskView,
                                    finishTransitionRunnable);
                            finishTransitionPosted = true;
                        }
                    }
                }
                transaction.apply();
            }
        }
        if (!finishTransitionPosted) {
            // If we haven't posted the transition end runnable, run it now
            finishTransitionRunnable.run();
        }
        doLogGesture(true /* toLauncher */);
    }

    private void setupLauncherUiAfterSwipeUpAnimation() {
        mActivityControlHelper.onSwipeUpComplete(mActivity);

        // Animate the first icon.
        mRecentsView.setFirstTaskIconScaledDown(false /* isScaledDown */, true /* animate */);

        reset();
    }

    public void onQuickScrubEnd() {
        setStateOnUiThread(STATE_QUICK_SCRUB_END);
    }

    private void switchToFinalAppAfterQuickSwitch() {
        mQuickScrubController.onQuickSwitch();
    }

    private void switchToFinalAppAfterQuickScrub() {
        mQuickScrubController.onQuickScrubEnd();

        // Normally this is handled in reset(), but since we are still scrubbing after the
        // transition into recents, we need to defer the handler invalidation for quick scrub until
        // after the gesture ends
        setStateOnUiThread(STATE_HANDLER_INVALIDATED);
    }

    public void onQuickScrubProgress(float progress) {
        if (Looper.myLooper() != Looper.getMainLooper() || mQuickScrubController == null) {
            // TODO: We can still get progress events while launcher is not ready on the worker
            // thread. Keep track of last received progress and apply that progress when launcher
            // is ready
            return;
        }
        mQuickScrubController.onQuickScrubProgress(progress);
    }

    private void debugNewState(int stateFlag) {
        if (!DEBUG_STATES) {
            return;
        }

        int state = mStateCallback.getState();
        StringJoiner currentStateStr = new StringJoiner(", ", "[", "]");
        String stateFlagStr = "Unknown-" + stateFlag;
        for (int i = 0; i < STATES.length; i++) {
            if ((state & (i << i)) != 0) {
                currentStateStr.add(STATES[i]);
            }
            if (stateFlag == (1 << i)) {
                stateFlagStr = STATES[i] + " (" + stateFlag + ")";
            }
        }
        Log.d(TAG, "[" + System.identityHashCode(this) + "] Adding " + stateFlagStr + " to "
                + currentStateStr);
    }

    public void setGestureEndCallback(Runnable gestureEndCallback) {
        mGestureEndCallback = gestureEndCallback;
    }
}
