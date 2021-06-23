package com.relywisdom.usbwebrtc;

import android.content.Context;
import android.hardware.Camera;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.usb.UsbManager;
import android.os.SystemClock;

import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerationAndroid;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.Logging;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.annotation.Nullable;

public class UsbCameraEnumerator extends Camera2Enumerator {
    private static UsbManager mUsbManager;
    private static CameraManager cameraManager;

    public  UsbCameraEnumerator(Context context) {
        super(context);
        mUsbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        cameraManager = (CameraManager)context.getSystemService(Context.CAMERA_SERVICE);
    }

    public static final String cameraName = "usbCamera";

    @Override
    public String[] getDeviceNames() {
        String[] deviceIds = super.getDeviceNames();

        String[] allCameras = new String[hasUsbDevice() ? deviceIds.length + 1 : deviceIds.length];
        int counter = 0;
        for (String cameraId: deviceIds) {
            try {
                allCameras[counter] = getDeviceName(counter);
            } catch (Exception e) {
                Logging.e("UsbCameraEnumerator", "getDeviceName failed on index/id: " + cameraId);
                return null;
            }
            counter++;
        }

        if (hasUsbDevice()) {
            allCameras[allCameras.length - 1] = cameraName;
        }

        return allCameras;
    }

    public boolean hasUsbDevice() {
        return mUsbManager.getDeviceList().size() > 0;
    }

    @Override
    public boolean isFrontFacing(String deviceName) {
        if (cameraName.equals(deviceName)) return false;
        try {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(String.valueOf(getCameraIndex(deviceName)));
            return characteristics != null && characteristics.get(CameraCharacteristics.LENS_FACING) == 0;
        } catch (CameraAccessException e) {
            throw new IllegalArgumentException("No permissions to access device camera");
        }
    }

    @Override
    public boolean isBackFacing(String deviceName) {
        if (cameraName.equals(deviceName)) return false;
        try {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(deviceName);
            return characteristics != null && (Integer)characteristics.get(CameraCharacteristics.LENS_FACING) == 1;
        } catch (CameraAccessException e) {
            throw new IllegalArgumentException("No permissions to access device camera");
        }
    }

    @Override
    public List<CameraEnumerationAndroid.CaptureFormat> getSupportedFormats(String deviceName) {
        if (cameraName.equals(deviceName)) return new ArrayList<>();
        return super.getSupportedFormats(String.valueOf(getCameraIndex(deviceName)));
    }

    @Override
    public CameraVideoCapturer createCapturer(String deviceName, CameraVideoCapturer.CameraEventsHandler eventsHandler) {
        if (deviceName.equals(cameraName)) {
            return new UsbCameraCapturer(deviceName, eventsHandler, this);
        } else {
            return super.createCapturer(String.valueOf(getCameraIndex(deviceName)), eventsHandler);
        }
    }

    static List<org.webrtc.Size> convertSizes(List<Camera.Size> cameraSizes) {
        List<org.webrtc.Size> sizes = new ArrayList();
        Iterator var2 = cameraSizes.iterator();

        while(var2.hasNext()) {
            Camera.Size size = (Camera.Size)var2.next();
            sizes.add(new org.webrtc.Size(size.width, size.height));
        }

        return sizes;
    }

    static List<CameraEnumerationAndroid.CaptureFormat.FramerateRange> convertFramerates(List<int[]> arrayRanges) {
        List<CameraEnumerationAndroid.CaptureFormat.FramerateRange> ranges = new ArrayList();
        Iterator var2 = arrayRanges.iterator();

        while(var2.hasNext()) {
            int[] range = (int[])var2.next();
            ranges.add(new CameraEnumerationAndroid.CaptureFormat.FramerateRange(range[0], range[1]));
        }

        return ranges;
    }

    static int getCameraIndex(String deviceName) {
        Logging.d("UsbCameraEnumerator", "getCameraIndex: " + deviceName);
        if (cameraName.equals(deviceName)) return mUsbManager.getDeviceList().size();

        try {
            for(int i = 0; i < cameraManager.getCameraIdList().length; ++i) {
                try {
                    if (deviceName.equals(getDeviceName(i))) {
                        return i;
                    }
                } catch (Exception e) {
                    Logging.d("UsbCameraEnumerator", "Get camera index for camera index " + deviceName + " failed: " + e.getMessage());
                    throw new IllegalArgumentException("Cannot get camera index for: " + deviceName);
                }
            }
        } catch (Exception e) {
            Logging.d("UsbCameraEnumerator", "Cannot access camera list: " + e.getMessage());
            throw new IllegalArgumentException("Cannot access camera list: " + e.getMessage());
        }

        throw new IllegalArgumentException("No such camera: " + deviceName);
    }

    @Nullable
    static String getDeviceName(int index) throws CameraAccessException {
        if (index == cameraManager.getCameraIdList().length) return cameraName;
        CameraCharacteristics info = getCameraInfo(index);
        if (info == null) {
            return null;
        } else {
            String facing = info.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT ? "front" : "back";
            return "Camera " + index + ", Facing " + facing + ", Orientation " + facing;
        }
    }

    @Nullable
    private static CameraCharacteristics getCameraInfo(int index) {
        try {
            return cameraManager.getCameraCharacteristics(String.valueOf(index));
        } catch (Exception var3) {
            Logging.e("UsbCameraEnumerator", "getCameraInfo failed on index " + index, var3);
            return null;
        }
    }
}
