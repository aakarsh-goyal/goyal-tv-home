package com.gathin.tvhome;

import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/** Reduced motion (config.xml reduced_motion): the one spring and the one time limit every animation uses then. */
final class Motion {

    /** The longest any reduced-motion animation runs, in milliseconds. */
    static final int REDUCED_MS = 90;
    /** Critically damped (no bounce) and stiff enough that any move, even across the screen, settles in 5 frames. */
    static final float REDUCED_STIFFNESS = 15000f;
    /** The spring stops once it is within this many pixels of its target. */
    static final float REDUCED_MIN_CHANGE = 2f;

    /** Apple's standard ease-out (0.22, 1, 0.36, 1): used by every timed move that is not a spring. */
    static final Interpolator EASE = new PathInterpolator(0.22f, 1f, 0.36f, 1f);
    /** One spring for everything that follows focus (tile, highlight, shelf), so they move as one. */
    static final float FOCUS_STIFFNESS = 650f;
    /** Slight settle (about 1% overshoot) when focus_overshoot is on... */
    static final float FOCUS_DAMPING_SOFT = 0.74f;
    /** ...and none (critically damped) when it is off. */
    static final float FOCUS_DAMPING = 1f;

    private Motion() {
    }

    static SpringForce focus(boolean overshoot) {
        return new SpringForce().setStiffness(FOCUS_STIFFNESS)
                .setDampingRatio(overshoot ? FOCUS_DAMPING_SOFT : FOCUS_DAMPING);
    }

    /** Scroll time grows with distance but stays brisk: 300 ms for a step, never more than 460 ms. */
    static int scrollMs(int distance) {
        return Math.max(300, Math.min(460, 240 + (int) (Math.abs(distance) * 0.12f)));
    }

    static void reduce(SpringAnimation spring) {
        spring.setSpring(new SpringForce().setStiffness(REDUCED_STIFFNESS).setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY));
        spring.setMinimumVisibleChange(REDUCED_MIN_CHANGE);
    }
}
