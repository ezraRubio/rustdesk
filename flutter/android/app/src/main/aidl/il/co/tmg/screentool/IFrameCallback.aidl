package il.co.tmg.screentool;

import android.os.SharedMemory;
import il.co.tmg.screentool.DirtyRegionData;

interface IFrameCallback {
    void onFrameAvailable(in SharedMemory sharedMemory, in DirtyRegionData dirtyRegion);
    void onCaptureError(String error);
}
