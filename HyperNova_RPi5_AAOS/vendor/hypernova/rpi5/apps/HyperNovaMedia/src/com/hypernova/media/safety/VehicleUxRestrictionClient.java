package com.hypernova.media.safety;

import android.car.Car;
import android.car.drivingstate.CarUxRestrictions;
import android.car.drivingstate.CarUxRestrictionsManager;
import android.content.Context;
import android.util.Log;

/** Small lifecycle wrapper around AAOS driving-distraction restrictions. */
public final class VehicleUxRestrictionClient implements AutoCloseable {
    private static final String TAG = "HyperNovaMedia";

    public interface Listener {
        void onVideoRestrictionChanged(boolean videoRestricted);
    }

    private final Listener mListener;
    private Car mCar;
    private CarUxRestrictionsManager mManager;
    private boolean mVideoRestricted;

    public VehicleUxRestrictionClient(Context context, Listener listener) {
        mListener = listener;
        try {
            mCar = Car.createCar(context);
            if (mCar != null) {
                mManager = (CarUxRestrictionsManager) mCar.getCarManager(
                        Car.CAR_UX_RESTRICTION_SERVICE);
                if (mManager != null) {
                    mManager.registerListener(this::updateRestrictions);
                    updateRestrictions(mManager.getCurrentCarUxRestrictions());
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Car UX restrictions unavailable", e);
        }
    }

    public boolean isVideoRestricted() {
        return mVideoRestricted;
    }

    private void updateRestrictions(CarUxRestrictions restrictions) {
        boolean restricted = restrictions != null
                && restrictions.isRequiresDistractionOptimization();
        if (mVideoRestricted != restricted) {
            mVideoRestricted = restricted;
            mListener.onVideoRestrictionChanged(restricted);
        }
    }

    @Override
    public void close() {
        if (mManager != null) {
            try {
                mManager.unregisterListener();
            } catch (RuntimeException ignored) {
            }
            mManager = null;
        }
        if (mCar != null) {
            mCar.disconnect();
            mCar = null;
        }
    }
}
