package il.co.tmg.screentool;

import il.co.tmg.screentool.IFrameCallback;

interface ICaptureService {
    void initCapture();
    void releaseCapture();
    
    void registerFrameCallback(IFrameCallback callback);
    void unregisterFrameCallback();
    
    int getScreenWidth();
    int getScreenHeight();

    void injectPointer(int kind, int mask, int x, int y, boolean wakeUp);
    void injectKeyEvent(int keyCode, int modifiers, boolean sendDown, boolean sendUp);
}
