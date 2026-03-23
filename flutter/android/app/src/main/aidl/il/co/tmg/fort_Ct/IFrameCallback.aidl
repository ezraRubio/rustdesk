package il.co.tmg.fort_ct;

import android.os.SharedMemory;

interface IFrameCallback {
    void onFrameAvailable(in SharedMemory sharedMemory);
    void onCaptureError(String error);
}
