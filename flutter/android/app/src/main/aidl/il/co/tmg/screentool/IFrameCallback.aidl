package il.co.tmg.screentool;

import android.os.SharedMemory;

interface IFrameCallback {
    void onFrameAvailable(in SharedMemory sharedMemory);
    void onCaptureError(String error);
}
