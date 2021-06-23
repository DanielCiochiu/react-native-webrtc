package com.relywisdom.usbwebrtc;


import org.webrtc.VideoFrame;

interface UsbCameraSession {
    void stop();

    public interface Events {
        void onCameraOpening();

        void onCameraError(UsbCameraSession session, String error);

        void onCameraClosed(UsbCameraSession session);

        void onFrameCaptured(UsbCameraSession sesison, VideoFrame frame);
    }

    public interface CreateSessionCallback {
        void onDone(UsbCameraSession session);
        void onFailure(String error);
    }

    public static enum FailureType {
        ERROR,
        DISCONNECTED;

        private FailureType() {
        }
    }
}
