package il.co.tmg.fort_ct;

import il.co.tmg.fort_ct.IFrameCallback;
import android.view.KeyEvent as KeyEventAndroid

interface ICaptureService {
    void initCapture();
    void releaseCapture();
    
    void registerFrameCallback(IFrameCallback callback);
    void unregisterFrameCallback();
    
    int getScreenWidth();
    int getScreenHeight();

    void injectPointer(int kind, int mask, int x, int y, boolean wakeUp);
    //void injectKeyEvent(int keyCode, int modifiers, boolean sendDown, boolean sendUp);
    void injectKeyEvent(event KeyEventAndroid);
}
