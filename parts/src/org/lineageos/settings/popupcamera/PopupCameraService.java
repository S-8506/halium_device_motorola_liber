/*
 * Copyright (C) 2019 The LineageOS Project
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

package org.lineageos.settings.popupcamera;

import android.annotation.NonNull;
import android.app.Service;
import android.content.Intent;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;

import org.lineageos.settings.R;
import org.lineageos.settings.utils.FileUtils;

import vendor.motorola.hardware.motor.V1_0.IMotor;

public class PopupCameraService extends Service implements Handler.Callback {

    private static final String TAG = "PopupCameraService";
    private static final boolean DEBUG = false;

    private boolean mMotorBusy = false;
    private long mClosedEvent;
    private long mOpenEvent;

    private Handler mHandler = new Handler(this);
    private IMotor mMotor = null;
    private PopupCameraPreferences mPopupCameraPreferences;
    private SensorManager mSensorManager;
    private Sensor mFreeFallSensor;

    private CameraManager.AvailabilityCallback availabilityCallback =
            new CameraManager.AvailabilityCallback() {
                @Override
                public void onCameraAvailable(@NonNull String cameraId) {
                    super.onCameraAvailable(cameraId);
                    if (cameraId.equals(Constants.FRONT_CAMERA_ID)) {
                        mClosedEvent = SystemClock.elapsedRealtime();
                        if (SystemClock.elapsedRealtime() - mOpenEvent
                                < Constants.CAMERA_EVENT_DELAY_TIME && mHandler.hasMessages(
                                Constants.MSG_CAMERA_OPEN)) {
                            mHandler.removeMessages(Constants.MSG_CAMERA_OPEN);
                        }
                        mHandler.sendEmptyMessageDelayed(Constants.MSG_CAMERA_CLOSED,
                                Constants.CAMERA_EVENT_DELAY_TIME);
                    }
                }

                @Override
                public void onCameraUnavailable(@NonNull String cameraId) {
                    super.onCameraAvailable(cameraId);
                    if (cameraId.equals(Constants.FRONT_CAMERA_ID)) {
                        mOpenEvent = SystemClock.elapsedRealtime();
                        if (SystemClock.elapsedRealtime() - mClosedEvent
                                < Constants.CAMERA_EVENT_DELAY_TIME && mHandler.hasMessages(
                                Constants.MSG_CAMERA_CLOSED)) {
                            mHandler.removeMessages(Constants.MSG_CAMERA_CLOSED);
                        }
                        mHandler.sendEmptyMessageDelayed(Constants.MSG_CAMERA_OPEN,
                                Constants.CAMERA_EVENT_DELAY_TIME);
                    }
                }
            };

    private SensorEventListener mFreeFallListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event.values[0] == 2.0f) {
                updateMotor(Constants.CLOSE_CAMERA_STATE);
                goBackHome();
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    @Override
    public void onCreate() {
        CameraManager cameraManager = getSystemService(CameraManager.class);
        cameraManager.registerAvailabilityCallback(availabilityCallback, null);
        mSensorManager = getSystemService(SensorManager.class);
        mFreeFallSensor = mSensorManager.getDefaultSensor(Constants.FREE_FALL_SENSOR_ID);

        try {
            mMotor = IMotor.getService();
            int status = mMotor.getMotorStatus();
            if (status == Constants.MOTOR_STATUS_POPUP || status == Constants.MOTOR_STATUS_POPUP_JAM
                    || status == Constants.MOTOR_STATUS_TAKEBACK_JAM) {
                mHandler.sendEmptyMessage(Constants.MSG_CAMERA_CLOSED);
            }
        } catch (RemoteException e) {
            // Do nothing
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (DEBUG) Log.d(TAG, "Starting service");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (DEBUG) Log.d(TAG, "Destroying service");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void updateMotor(String cameraState) {
        if (mMotor == null) {
            return;
        }
        final Runnable r = () -> {
            mMotorBusy = true;
            mHandler.postDelayed(() -> mMotorBusy = false, 1200);
            try {
                if (cameraState.equals(Constants.OPEN_CAMERA_STATE)
                        && mMotor.getMotorStatus() == Constants.MOTOR_STATUS_TAKEBACK) {
                    mMotor.popupMotor(1);
                    mSensorManager.registerListener(mFreeFallListener, mFreeFallSensor,
                            SensorManager.SENSOR_DELAY_NORMAL);
                } else if (cameraState.equals(Constants.CLOSE_CAMERA_STATE)
                        && mMotor.getMotorStatus() == Constants.MOTOR_STATUS_POPUP) {
                    mMotor.takebackMotor(1);
                    mSensorManager.unregisterListener(mFreeFallListener, mFreeFallSensor);
                }
            } catch (RemoteException e) {
                // Do nothing
            }
        };

        if (mMotorBusy) {
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (mMotorBusy) {
                        mHandler.postDelayed(this, 100);
                    } else {
                        mHandler.post(r);
                    }
                }
            }, 100);
        } else {
            mHandler.post(r);
        }
    }

    public void goBackHome() {
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivityAsUser(homeIntent, null, UserHandle.CURRENT);
    }

    @Override
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case Constants.MSG_CAMERA_CLOSED: {
                updateMotor(Constants.CLOSE_CAMERA_STATE);
            }
            break;
            case Constants.MSG_CAMERA_OPEN: {
                updateMotor(Constants.OPEN_CAMERA_STATE);
            }
            break;
        }
        return true;
    }
}
