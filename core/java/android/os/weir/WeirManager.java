package android.os.weir;

import android.content.Context;
import android.util.Log;
import android.os.Handler;
import android.os.RemoteException;

public final class WeirManager {
    private static final String TAG = "WeirManager";

    final Context mContext;
    final IWeirManager mService;
    final Handler mHandler;

    /**
     * {@hide}
     */
    public WeirManager(Context context, IWeirManager service, Handler handler) {
        mContext = context;
        mService = service;
        mHandler = handler;
    }

    public void hello() {
        try {
            mService.hello();
        } catch (RemoteException e) {
        }
    }
    public void createTag(String tagName, boolean globalPos, boolean globalNeg, String[] posCapAlloc, String[] negCapAlloc, String[] domains) {
       try {
            mService.createTag(tagName, globalPos, globalNeg, posCapAlloc, negCapAlloc, domains);
        } catch (RemoteException e) {
        }
    }
    public void addTagToLabel(String ownerPackageName, String tagName) {
	try {
            mService.addTagToLabel(ownerPackageName, tagName);
        } catch (RemoteException e) {
        }
    }
    public boolean checkAdd(String ownerPackageName, String tagName) {
	try {
            return mService.checkAdd(ownerPackageName, tagName);
        } catch (RemoteException e) {
        }
	return false;
    }

}
