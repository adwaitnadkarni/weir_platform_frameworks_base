package com.android.server.weir;

import java.util.Random;
import android.content.Context;
import android.content.pm.PackageManager;

public class WeirHelper {
    private static final String TAG = "WeirUtility";
    private Random random;
    private Context context;
    PackageManager pm = null;

    public WeirHelper(Context context){
	this.context = context;
	random = new Random();
	pm = context.getPackageManager();
    }
    //Get a random long
    public long getRandomTag(){
	return random.nextLong();
    }

    public String getPackageName(int uid){
	String packageName = null;
	if(pm==null){
	    pm = context.getPackageManager();
	}
	try {
	    packageName = pm.getPackagesForUid(uid)[0];
	} catch (Exception e){
	    e.printStackTrace();
	}

	return packageName;
    }
}
