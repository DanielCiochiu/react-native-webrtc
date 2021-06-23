package com.relywisdom.usbwebrtc;

//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by Fernflower decompiler)
//


import android.content.Context;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import org.webrtc.CameraEnumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.CapturerObserver;
import org.webrtc.Logging;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoFrame;

import java.util.Arrays;

import javax.annotation.Nullable;

class UsbCameraCapturer implements CameraVideoCapturer {
    private static final String TAG = "UsbCameraCapturer";
    private static final int MAX_OPEN_CAMERA_ATTEMPTS = 3;
    private static final int OPEN_CAMERA_DELAY_MS = 500;
    private static final int OPEN_CAMERA_TIMEOUT = 10000;
    private final CameraEnumerator cameraEnumerator;
    @Nullable
    private final CameraEventsHandler eventsHandler;
    private final Handler uiThreadHandler;
    @Nullable
    private final UsbCameraSession.CreateSessionCallback createSessionCallback = new UsbCameraSession.CreateSessionCallback() {
        public void onDone(UsbCameraSession session) {
            checkIsOnCameraThread();
            Logging.d("UsbCameraCapturer", "Create session done. Switch state: " + switchState + ". MediaRecorder state: " + mediaRecorderState);
            uiThreadHandler.removeCallbacks(openCameraTimeoutRunnable);
            synchronized(stateLock) {
                capturerObserver.onCapturerStarted(true);
                sessionOpening = false;
                currentSession = session;
                cameraStatistics = new CameraStatistics(surfaceHelper, eventsHandler);
                firstFrameObserved = false;
                stateLock.notifyAll();
                if (switchState == UsbCameraCapturer.SwitchState.IN_PROGRESS) {
                    if (switchEventsHandler != null) {
                        switchEventsHandler.onCameraSwitchDone(cameraEnumerator.isFrontFacing(cameraName));
                        switchEventsHandler = null;
                    }

                    switchState = UsbCameraCapturer.SwitchState.IDLE;
                } else if (switchState == UsbCameraCapturer.SwitchState.PENDING) {
                    switchState = UsbCameraCapturer.SwitchState.IDLE;
                    switchCameraInternal(switchEventsHandler);
                }

                if (mediaRecorderState == UsbCameraCapturer.MediaRecorderState.IDLE_TO_ACTIVE || mediaRecorderState == UsbCameraCapturer.MediaRecorderState.ACTIVE_TO_IDLE) {
                    if (mediaRecorderEventsHandler != null) {
                        mediaRecorderEventsHandler.onMediaRecorderSuccess();
                        mediaRecorderEventsHandler = null;
                    }

                    if (mediaRecorderState == UsbCameraCapturer.MediaRecorderState.IDLE_TO_ACTIVE) {
                        mediaRecorderState = UsbCameraCapturer.MediaRecorderState.ACTIVE;
                    } else {
                        mediaRecorderState = UsbCameraCapturer.MediaRecorderState.IDLE;
                    }
                }

            }
        }

        @Override
        public void onFailure(String error) {

        }

        public void onFailure(UsbCameraSession.FailureType failureType, String error) {
            checkIsOnCameraThread();
            uiThreadHandler.removeCallbacks(openCameraTimeoutRunnable);
            synchronized(stateLock) {
                capturerObserver.onCapturerStarted(false);
                openAttemptsRemaining--;
                if (openAttemptsRemaining <= 0) {
                    Logging.w("UsbCameraCapturer", "Opening camera failed, passing: " + error);
                    sessionOpening = false;
                    stateLock.notifyAll();
                    if (switchState != UsbCameraCapturer.SwitchState.IDLE) {
                        if (switchEventsHandler != null) {
                            switchEventsHandler.onCameraSwitchError(error);
                            switchEventsHandler = null;
                        }

                        switchState = UsbCameraCapturer.SwitchState.IDLE;
                    }

                    if (mediaRecorderState != UsbCameraCapturer.MediaRecorderState.IDLE) {
                        if (mediaRecorderEventsHandler != null) {
                            mediaRecorderEventsHandler.onMediaRecorderError(error);
                            mediaRecorderEventsHandler = null;
                        }

                        mediaRecorderState = UsbCameraCapturer.MediaRecorderState.IDLE;
                    }

                    if (failureType == UsbCameraSession.FailureType.DISCONNECTED) {
                        eventsHandler.onCameraDisconnected();
                    } else {
                        eventsHandler.onCameraError(error);
                    }
                } else {
                    Logging.w("UsbCameraCapturer", "Opening camera failed, retry: " + error);
                    createSessionInternal(500, (MediaRecorder)null);
                }

            }
        }
    };
    @Nullable
    private final UsbCameraSession.Events cameraSessionEventsHandler = new UsbCameraSession.Events() {
        public void onCameraOpening() {
            checkIsOnCameraThread();
            synchronized(stateLock) {
                if (currentSession != null) {
                    Logging.w("UsbCameraCapturer", "onCameraOpening while session was open.");
                } else {
                    eventsHandler.onCameraOpening(cameraName);
                }
            }
        }

        public void onCameraError(UsbCameraSession session, String error) {
            checkIsOnCameraThread();
            synchronized(stateLock) {
                if (session != currentSession) {
                    Logging.w("UsbCameraCapturer", "onCameraError from another session: " + error);
                } else {
                    eventsHandler.onCameraError(error);
                    stopCapture();
                }
            }
        }

        public void onCameraDisconnected(UsbCameraSession session) {
            checkIsOnCameraThread();
            synchronized(stateLock) {
                if (session != currentSession) {
                    Logging.w("UsbCameraCapturer", "onCameraDisconnected from another session.");
                } else {
                    eventsHandler.onCameraDisconnected();
                    stopCapture();
                }
            }
        }

        public void onCameraClosed(UsbCameraSession session) {
            checkIsOnCameraThread();
            synchronized(stateLock) {
                if (session != currentSession && currentSession != null) {
                    Logging.d("UsbCameraCapturer", "onCameraClosed from another session.");
                } else {
                    eventsHandler.onCameraClosed();
                }
            }
        }

        public void onFrameCaptured(UsbCameraSession session, VideoFrame frame) {
            checkIsOnCameraThread();
            synchronized(stateLock) {
                if (session != currentSession) {
                    Logging.w("UsbCameraCapturer", "onTextureFrameCaptured from another session.");
                } else {
                    if (!firstFrameObserved) {
                        eventsHandler.onFirstFrameAvailable();
                        firstFrameObserved = true;
                    }

                    if (cameraStatistics != null) {
                        cameraStatistics.addFrame();
                        capturerObserver.onFrameCaptured(frame);
                    }
                }
            }
        }
    };
    private final Runnable openCameraTimeoutRunnable = new Runnable() {
        public void run() {
            eventsHandler.onCameraError("Camera failed to start within timeout.");
        }
    };
    @Nullable
    private Handler cameraThreadHandler;
    private Context applicationContext;
    private CapturerObserver capturerObserver;
    @Nullable
    private SurfaceTextureHelper surfaceHelper;
    private final Object stateLock = new Object();
    private boolean sessionOpening;
    @Nullable
    private UsbCameraSession currentSession;
    private String cameraName;
    private int width;
    private int height;
    private int framerate;
    private int openAttemptsRemaining;
    private UsbCameraCapturer.SwitchState switchState;
    @Nullable
    private CameraSwitchHandler switchEventsHandler;
    @Nullable
    private CameraStatistics cameraStatistics;
    private boolean firstFrameObserved;
    private UsbCameraCapturer.MediaRecorderState mediaRecorderState;
    @Nullable
    private MediaRecorderHandler mediaRecorderEventsHandler;

    public UsbCameraCapturer(String cameraName, @Nullable CameraEventsHandler eventsHandler, CameraEnumerator cameraEnumerator) {
        this.switchState = UsbCameraCapturer.SwitchState.IDLE;
        this.mediaRecorderState = UsbCameraCapturer.MediaRecorderState.IDLE;
        if (eventsHandler == null) {
            eventsHandler = new CameraEventsHandler() {
                public void onCameraError(String errorDescription) {
                }

                public void onCameraDisconnected() {
                }

                public void onCameraFreezed(String errorDescription) {
                }

                public void onCameraOpening(String cameraName) {
                }

                public void onFirstFrameAvailable() {
                }

                public void onCameraClosed() {
                }
            };
        }

        this.eventsHandler = eventsHandler;
        this.cameraEnumerator = cameraEnumerator;
        this.cameraName = cameraName;
        this.uiThreadHandler = new Handler(Looper.getMainLooper());
        String[] deviceNames = cameraEnumerator.getDeviceNames();
        if (deviceNames.length == 0) {
            throw new RuntimeException("No cameras attached.");
        } else if (!Arrays.asList(deviceNames).contains(this.cameraName)) {
            throw new IllegalArgumentException("Camera name " + this.cameraName + " does not match any known camera device.");
        }
    }

    public void initialize(@Nullable SurfaceTextureHelper surfaceTextureHelper, Context applicationContext, CapturerObserver capturerObserver) {
        this.applicationContext = applicationContext;
        this.capturerObserver = capturerObserver;
        this.surfaceHelper = surfaceTextureHelper;
        this.cameraThreadHandler = surfaceTextureHelper == null ? null : surfaceTextureHelper.getHandler();
    }

    public void startCapture(int width, int height, int framerate) {
        Logging.d("UsbCameraCapturer", "startCapture: " + width + "x" + height + "@" + framerate);
        if (this.applicationContext == null) {
            throw new RuntimeException("UsbCameraCapturer must be initialized before calling startCapture.");
        } else {
            Object var4 = this.stateLock;
            synchronized(this.stateLock) {
                if (!this.sessionOpening && this.currentSession == null) {
                    this.width = width;
                    this.height = height;
                    this.framerate = framerate;
                    this.sessionOpening = true;
                    this.openAttemptsRemaining = 3;
                    this.createSessionInternal(0, (MediaRecorder)null);
                } else {
                    Logging.w("UsbCameraCapturer", "Session already open");
                }
            }
        }
    }

    private void createSessionInternal(int delayMs, final MediaRecorder mediaRecorder) {
        this.uiThreadHandler.postDelayed(this.openCameraTimeoutRunnable, (long)(delayMs + 10000));
        this.cameraThreadHandler.postDelayed(new Runnable() {
            public void run() {
                createCameraSession(createSessionCallback, cameraSessionEventsHandler, applicationContext, surfaceHelper, cameraName, width, height, framerate);
            }
        }, (long)delayMs);
    }

    public void stopCapture() {
        Logging.d("UsbCameraCapturer", "Stop capture");
        Object var1 = this.stateLock;
        synchronized(this.stateLock) {
            while(this.sessionOpening) {
                Logging.d("UsbCameraCapturer", "Stop capture: Waiting for session to open");

                try {
                    this.stateLock.wait();
                } catch (InterruptedException var4) {
                    Logging.w("UsbCameraCapturer", "Stop capture interrupted while waiting for the session to open.");
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            if (this.currentSession != null) {
                Logging.d("UsbCameraCapturer", "Stop capture: Nulling session");
                this.cameraStatistics.release();
                this.cameraStatistics = null;
                final UsbCameraSession oldSession = this.currentSession;
                this.cameraThreadHandler.post(new Runnable() {
                    public void run() {
                        oldSession.stop();
                    }
                });
                this.currentSession = null;
                this.capturerObserver.onCapturerStopped();
            } else {
                Logging.d("UsbCameraCapturer", "Stop capture: No session open");
            }
        }

        Logging.d("UsbCameraCapturer", "Stop capture done");
    }

    public void changeCaptureFormat(int width, int height, int framerate) {
        Logging.d("UsbCameraCapturer", "changeCaptureFormat: " + width + "x" + height + "@" + framerate);
        Object var4 = this.stateLock;
        synchronized(this.stateLock) {
            this.stopCapture();
            this.startCapture(width, height, framerate);
        }
    }

    public void dispose() {
        Logging.d("UsbCameraCapturer", "dispose");
        this.stopCapture();
    }

    public void switchCamera(final CameraSwitchHandler switchEventsHandler) {
        Logging.d("UsbCameraCapturer", "switchCamera");
        this.cameraThreadHandler.post(new Runnable() {
            public void run() {
                switchCameraInternal(switchEventsHandler);
            }
        });
    }

    @Override
    public void switchCamera(CameraSwitchHandler cameraSwitchHandler, String s) {
        switchCamera(cameraSwitchHandler);
    }

    public void addMediaRecorderToCamera(final MediaRecorder mediaRecorder, final MediaRecorderHandler mediaRecoderEventsHandler) {
        Logging.d("UsbCameraCapturer", "addMediaRecorderToCamera");
        this.cameraThreadHandler.post(new Runnable() {
            public void run() {
                updateMediaRecorderInternal(mediaRecorder, mediaRecoderEventsHandler);
            }
        });
    }

    public void removeMediaRecorderFromCamera(final MediaRecorderHandler mediaRecoderEventsHandler) {
        Logging.d("UsbCameraCapturer", "removeMediaRecorderFromCamera");
        this.cameraThreadHandler.post(new Runnable() {
            public void run() {
                updateMediaRecorderInternal((MediaRecorder)null, mediaRecoderEventsHandler);
            }
        });
    }

    public boolean isScreencast() {
        return false;
    }

    private void reportCameraSwitchError(String error, @Nullable CameraSwitchHandler switchEventsHandler) {
        Logging.e("UsbCameraCapturer", error);
        if (switchEventsHandler != null) {
            switchEventsHandler.onCameraSwitchError(error);
        }

    }

    private void switchCameraInternal(@Nullable CameraSwitchHandler switchEventsHandler) {
        Logging.d("UsbCameraCapturer", "switchCamera internal");
        String[] deviceNames = this.cameraEnumerator.getDeviceNames();
        if (deviceNames.length < 2) {
            if (switchEventsHandler != null) {
                switchEventsHandler.onCameraSwitchError("No camera to switch to.");
            }

        } else {
            Object var3 = this.stateLock;
            synchronized(this.stateLock) {
                if (this.switchState != UsbCameraCapturer.SwitchState.IDLE) {
                    this.reportCameraSwitchError("Camera switch already in progress.", switchEventsHandler);
                    return;
                }

                if (this.mediaRecorderState != UsbCameraCapturer.MediaRecorderState.IDLE) {
                    this.reportCameraSwitchError("switchCamera: media recording is active", switchEventsHandler);
                    return;
                }

                if (!this.sessionOpening && this.currentSession == null) {
                    this.reportCameraSwitchError("switchCamera: camera is not running.", switchEventsHandler);
                    return;
                }

                this.switchEventsHandler = switchEventsHandler;
                if (this.sessionOpening) {
                    this.switchState = UsbCameraCapturer.SwitchState.PENDING;
                    return;
                }

                this.switchState = UsbCameraCapturer.SwitchState.IN_PROGRESS;
                Logging.d("UsbCameraCapturer", "switchCamera: Stopping session");
                this.cameraStatistics.release();
                this.cameraStatistics = null;
                final UsbCameraSession oldSession = this.currentSession;
                this.cameraThreadHandler.post(new Runnable() {
                    public void run() {
                        oldSession.stop();
                        currentSession = null;
                        int cameraNameIndex = Arrays.asList(deviceNames).indexOf(cameraName);
                        cameraName = deviceNames[(cameraNameIndex + 1) % deviceNames.length];
                        sessionOpening = true;
                        openAttemptsRemaining = 1;
                        createSessionInternal(0, (MediaRecorder)null);
                    }
                });
            }

            Logging.d("UsbCameraCapturer", "switchCamera done");
        }
    }

    private void reportUpdateMediaRecorderError(String error, @Nullable MediaRecorderHandler mediaRecoderEventsHandler) {
        this.checkIsOnCameraThread();
        Logging.e("UsbCameraCapturer", error);
        if (mediaRecoderEventsHandler != null) {
            mediaRecoderEventsHandler.onMediaRecorderError(error);
        }

    }

    private void updateMediaRecorderInternal(@Nullable MediaRecorder mediaRecorder, MediaRecorderHandler mediaRecoderEventsHandler) {
        this.checkIsOnCameraThread();
        boolean addMediaRecorder = mediaRecorder != null;
        Logging.d("UsbCameraCapturer", "updateMediaRecoderInternal internal. State: " + this.mediaRecorderState + ". Switch state: " + this.switchState + ". Add MediaRecorder: " + addMediaRecorder);
        Object var4 = this.stateLock;
        synchronized(this.stateLock) {
            if (addMediaRecorder && this.mediaRecorderState != UsbCameraCapturer.MediaRecorderState.IDLE || !addMediaRecorder && this.mediaRecorderState != UsbCameraCapturer.MediaRecorderState.ACTIVE) {
                this.reportUpdateMediaRecorderError("Incorrect state for MediaRecorder update.", mediaRecoderEventsHandler);
                return;
            }

            if (this.switchState != UsbCameraCapturer.SwitchState.IDLE) {
                this.reportUpdateMediaRecorderError("MediaRecorder update while camera is switching.", mediaRecoderEventsHandler);
                return;
            }

            if (this.currentSession == null) {
                this.reportUpdateMediaRecorderError("MediaRecorder update while camera is closed.", mediaRecoderEventsHandler);
                return;
            }

            if (this.sessionOpening) {
                this.reportUpdateMediaRecorderError("MediaRecorder update while camera is still opening.", mediaRecoderEventsHandler);
                return;
            }

            this.mediaRecorderEventsHandler = mediaRecoderEventsHandler;
            this.mediaRecorderState = addMediaRecorder ? UsbCameraCapturer.MediaRecorderState.IDLE_TO_ACTIVE : UsbCameraCapturer.MediaRecorderState.ACTIVE_TO_IDLE;
            Logging.d("UsbCameraCapturer", "updateMediaRecoder: Stopping session");
            this.cameraStatistics.release();
            this.cameraStatistics = null;
            final UsbCameraSession oldSession = this.currentSession;
            this.cameraThreadHandler.post(new Runnable() {
                public void run() {
                    oldSession.stop();
                }
            });
            this.currentSession = null;
            this.sessionOpening = true;
            this.openAttemptsRemaining = 1;
            this.createSessionInternal(0, mediaRecorder);
        }

        Logging.d("UsbCameraCapturer", "updateMediaRecoderInternal done");
    }

    private void checkIsOnCameraThread() {
        if (Thread.currentThread() != this.cameraThreadHandler.getLooper().getThread()) {
            Logging.e("UsbCameraCapturer", "Check is on camera thread failed.");
            throw new RuntimeException("Not on camera thread.");
        }
    }

    protected String getCameraName() {
        Object var1 = this.stateLock;
        synchronized(this.stateLock) {
            return this.cameraName;
        }
    }

    protected void createCameraSession(UsbCameraSession.CreateSessionCallback createSessionCallback, UsbCameraSession.Events events, Context applicationContext,
                                       SurfaceTextureHelper surfaceTextureHelper, String cameraName, int width, int height, int framerate) {
        if (UsbCameraEnumerator.cameraName.equals(cameraName)) {
            UvcCameraSession.create(createSessionCallback, events, applicationContext, surfaceTextureHelper, width, height, framerate);
        }
        else {
            Camera1CopySession.create(createSessionCallback, events, false, applicationContext, surfaceTextureHelper, UsbCameraEnumerator.getCameraIndex(cameraName), width, height, framerate);
        }
    }

    static enum MediaRecorderState {
        IDLE,
        IDLE_TO_ACTIVE,
        ACTIVE_TO_IDLE,
        ACTIVE;

        private MediaRecorderState() {
        }
    }

    static enum SwitchState {
        IDLE,
        PENDING,
        IN_PROGRESS;

        private SwitchState() {
        }
    }
}
