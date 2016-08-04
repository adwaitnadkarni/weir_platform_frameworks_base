/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *	http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.am;

import android.content.ComponentName;
import android.os.Binder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Slog;
import android.util.SparseArray;
import com.android.internal.os.TransferPipe;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

//WEIR imports
import java.util.HashSet;
import android.os.weir.WeirLabelOps;

/**
 * Keeps track of content providers by authority (name) and class. It separates the mapping by
 * user and ones that are not user-specific (system providers).
 */
public final class ProviderMap {

    private static final String TAG = "ProviderMap";

    private static final boolean DBG = false;

    private final ActivityManagerService mAm;

    //WEIR:
    //This is similar to what we have done to the ServiceMap.
    //We add a label component to each of the HashMaps (even the ones in the
    //SparseArrays).  As a result, the ComponentNames and String will map to
    //HashSets of ContentProviderRecords (labeled).
    /*
    private final HashMap<String, ContentProviderRecord> mSingletonByName
	    = new HashMap<String, ContentProviderRecord>();
    private final HashMap<ComponentName, ContentProviderRecord> mSingletonByClass
	    = new HashMap<ComponentName, ContentProviderRecord>();

    private final SparseArray<HashMap<String, ContentProviderRecord>> mProvidersByNamePerUser
	    = new SparseArray<HashMap<String, ContentProviderRecord>>();
    private final SparseArray<HashMap<ComponentName, ContentProviderRecord>> mProvidersByClassPerUser
	    = new SparseArray<HashMap<ComponentName, ContentProviderRecord>>();
    */
    private final HashMap<String, HashSet<ContentProviderRecord>> mSingletonByName
	    = new HashMap<String, HashSet<ContentProviderRecord>>();
    private final HashMap<ComponentName, HashSet<ContentProviderRecord>> mSingletonByClass
	    = new HashMap<ComponentName, HashSet<ContentProviderRecord>>();

    private final SparseArray<HashMap<String, HashSet<ContentProviderRecord>>> mProvidersByNamePerUser
	    = new SparseArray<HashMap<String, HashSet<ContentProviderRecord>>>();
    private final SparseArray<HashMap<ComponentName, HashSet<ContentProviderRecord>>> mProvidersByClassPerUser
	    = new SparseArray<HashMap<ComponentName, HashSet<ContentProviderRecord>>>();

    ProviderMap(ActivityManagerService am) {
	mAm = am;
    }

    //WEIR: We must add methods that accept the caller's label, to handle
    //calls from the system context, but on behalf of non-system principals.
    ContentProviderRecord getProviderByName(String name, HashSet<Long> callerLabel) {
	return getProviderByName(name, -1, callerLabel);
    }
    ContentProviderRecord getProviderByClass(ComponentName name, HashSet<Long> callerLabel) {
	return getProviderByClass(name, -1, callerLabel);
    }
    //
    //WEIR: When the caller is not system, we can just get the caller's label by asking Weir.
    ContentProviderRecord getProviderByName(String name) {
	//WEIR: Get the caller's label
	HashSet<Long> callerLabel = mAm.getWeir().getProcessLabel(Binder.getCallingPid());
	return getProviderByName(name, -1, callerLabel);
    }
    ContentProviderRecord getProviderByClass(ComponentName name) {
	//WEIR: Get the caller's label
	HashSet<Long> callerLabel = mAm.getWeir().getProcessLabel(Binder.getCallingPid());
	return getProviderByClass(name, -1, callerLabel);
    }
    //
    //
    //WEIR: Add another set of prototypes to include  the userId, which also gets the label from Weir
    //
    ContentProviderRecord getProviderByName(String name, int userId) {
	//WEIR: Get the caller's label
	HashSet<Long> callerLabel = mAm.getWeir().getProcessLabel(Binder.getCallingPid());
	return getProviderByName(name, userId, callerLabel);
    }
    ContentProviderRecord getProviderByClass(ComponentName name, int userId) {
	//WEIR: Get the caller's label
	HashSet<Long> callerLabel = mAm.getWeir().getProcessLabel(Binder.getCallingPid());
	return getProviderByClass(name, userId, callerLabel);
    }
    //

    //WEIR: Finally, a set of prototypes that accepts the label from the caller.
    //
    //WEIR: Return the providerRecord with the matching callerLabel.
    ContentProviderRecord getProviderByName(String name, int userId, HashSet<Long> callerLabel) {
	if (DBG) {
	    Slog.i(TAG, "getProviderByName: " + name + " , callingUid = " + Binder.getCallingUid());
	}
	// Try to find it in the global list
	//WEIR: Compare labels.
	Slog.i(TAG, "getProviderByName: " + name + " , callingPid = " + Binder.getCallingPid());
	HashSet<ContentProviderRecord> records = mSingletonByName.get(name);
	if(records!=null){
	    for (ContentProviderRecord record : records){
		if(record==null)
		    continue;
		if(record.proc!=null){
		    Slog.i(TAG, "getProviderByName: " + name + " , getting label for pid = " + record.proc.pid);
		    record.proc.setSecrecyLabel(mAm.getWeir().getProcessLabel(record.proc.pid));
		    record.setSecrecyLabel(record.proc.getSecrecyLabel());
		}
		if(WeirLabelOps.equals(record.getSecrecyLabel(), callerLabel)){
		    return record;
		}    
	    }
	}

	// Check the current user's list
	//return getProvidersByName(userId).get(name);
	//WEIR: Only return the record with the matching label.
	records = getProvidersByName(userId).get(name);
	if(records!=null){
	    for (ContentProviderRecord record : records){
		if(record==null)
		    continue;
		if(record.proc!=null){
		    Slog.i(TAG, "getProviderByName: " + name + " , getting label for pid = " + record.proc.pid);
		    record.proc.setSecrecyLabel(mAm.getWeir().getProcessLabel(record.proc.pid));
		    record.setSecrecyLabel(record.proc.getSecrecyLabel());
		}
		if(WeirLabelOps.equals(record.getSecrecyLabel(), callerLabel)){
		    return record;
		}    
	    }
	}

	//Nothing found, return null.
	return null;
    }


    //WEIR: Same as done for getProviderByName
    ContentProviderRecord getProviderByClass(ComponentName name, int userId, HashSet<Long> callerLabel) {
	if (DBG) {
	    Slog.i(TAG, "getProviderByClass: " + name + ", callingUid = " + Binder.getCallingUid());
	}
	// Try to find it in the global list
	/*
	ContentProviderRecord record = mSingletonByClass.get(name);
	if (record != null) {
	    return record;
	}*/
	//WEIR: Compare labels.
	HashSet<ContentProviderRecord> records = mSingletonByClass.get(name);
	if(records!=null){
	    for (ContentProviderRecord record : records){
		if(record==null)
		    continue;
		if(record.proc!=null){
		    record.proc.setSecrecyLabel(mAm.getWeir().getProcessLabel(record.proc.pid));
		    record.setSecrecyLabel(record.proc.getSecrecyLabel());
		}
		if(WeirLabelOps.equals(record.getSecrecyLabel(), callerLabel)){
		    return record;
		}    
	    }
	}

	// Check the current user's list
	//return getProvidersByClass(userId).get(name);
	//WEIR: Only return the record with the matching label.
	records = getProvidersByClass(userId).get(name);
	if(records!=null){
	    for (ContentProviderRecord record : records){
		if(record==null)
		    continue;
		if(record.proc!=null){
		    record.proc.setSecrecyLabel(mAm.getWeir().getProcessLabel(record.proc.pid));
		    record.setSecrecyLabel(record.proc.getSecrecyLabel());
		}
		if(WeirLabelOps.equals(record.getSecrecyLabel(), callerLabel)){
		    return record;
		}    
	    }
	}

	//Nothing found, return null.
	return null;
    }

    //WEIR: Modify this to add the provider record to the HashSet.
    //NOTE: This method expects the caller to add the label to the record
    //before calling it.
    void putProviderByName(String name, ContentProviderRecord record) {
	if (DBG) {
	    Slog.i(TAG, "putProviderByName: " + name + " , callingUid = " + Binder.getCallingUid()
		+ ", record uid = " + record.appInfo.uid);
	}
	if (record.singleton) {
	    //WEIR: Add this to the hashset
	    HashSet<ContentProviderRecord> records = mSingletonByName.get(name);
	    if(records==null){
		records = new HashSet<ContentProviderRecord>();
	    }
	    records.add(record);
	    mSingletonByName.put(name, records);
	} else {
	    final int userId = UserHandle.getUserId(record.appInfo.uid);
	    //getProvidersByName(userId).put(name, record);
	    //WEIR: Add this to the hashset
	    HashSet<ContentProviderRecord> records = getProvidersByName(userId).get(name);
	    if(records==null){
		records = new HashSet<ContentProviderRecord>();
	    }
	    records.add(record);
	    getProvidersByName(userId).put(name, records);
	}
    }

    //WEIR: Same as done for putProviderByName
    void putProviderByClass(ComponentName name, ContentProviderRecord record) {
	if (DBG) {
	    Slog.i(TAG, "putProviderByClass: " + name + " , callingUid = " + Binder.getCallingUid()
		+ ", record uid = " + record.appInfo.uid);
	}
	if (record.singleton) {
	    //mSingletonByClass.put(name, record);
	    //WEIR: Add this to the hashset
	    HashSet<ContentProviderRecord> records = mSingletonByClass.get(name);
	    if(records==null){
		records = new HashSet<ContentProviderRecord>();
	    }
	    records.add(record);
	    mSingletonByClass.put(name, records);
	} else {
	    final int userId = UserHandle.getUserId(record.appInfo.uid);
	    //getProvidersByClass(userId).put(name, record);
	    //WEIR: Add this to the hashset
	    HashSet<ContentProviderRecord> records = getProvidersByClass(userId).get(name);
	    if(records==null){
		records = new HashSet<ContentProviderRecord>();
	    }
	    records.add(record);
	    getProvidersByClass(userId).put(name, records);
	}
    }

    //WEIR:  Called from contexts that need the provider (i.e., all of its
    //instances) to be removed. Se we modify the function prototype to include
    //the ProcessRecord, and remove only (all) those cprs that run in the
    //process.	In case the ProcessRecord is null (as when called from
    //forceStopPackageLocked), we remove ALL cprs for the name.
    void removeProviderByName(String name, int userId, ProcessRecord proc) {
	if (mSingletonByName.containsKey(name)) {
	    if (DBG)
		Slog.i(TAG, "Removing from globalByName name=" + name);
	    //mSingletonByName.remove(name);
	    //
	    if(proc == null){
		mSingletonByName.remove(name);
	    } else{
		HashSet<ContentProviderRecord> records = mSingletonByName.get(name);
		HashSet<ContentProviderRecord> toRemove = new HashSet<ContentProviderRecord>();
		if(records!=null){
		    //Mark records for removal
		    for (ContentProviderRecord record : records){
			if(record==null)
				continue;
			if(record.proc!=null){
			    if(record.proc.processName.equals(proc.processName)){
				toRemove.add(record);
			    }
			}
		    }

		    //Now remove the records marked for removal
		    for(ContentProviderRecord record: toRemove){
			records.remove(record);
		    }

		    //If no records are left in the hashset, remove it from the map.
		    if(records.isEmpty()){
			mSingletonByName.remove(name);
		    }
		}
	    }
	} else {
	    if (userId < 0) throw new IllegalArgumentException("Bad user " + userId);
	    if (DBG)
		Slog.i(TAG,
			"Removing from providersByName name=" + name + " user=" + userId);
	    HashMap<String, HashSet<ContentProviderRecord>> map = getProvidersByName(userId);
	    // map returned by getProvidersByName wouldn't be null
	    //map.remove(name);
	    if(proc == null){
		map.remove(name);
	    } else {
		HashSet<ContentProviderRecord> records = map.get(name);
		HashSet<ContentProviderRecord> toRemove = new HashSet<ContentProviderRecord>();
		if(records!=null){
		    //Mark records for removal
		    for (ContentProviderRecord record : records){
			if(record==null)
			    continue;
			if(record.proc!=null){
			    if(record.proc.processName.equals(proc.processName)){
				toRemove.add(record);
			    }
			}
		    }

		    //Now remove the records marked for removal
		    for(ContentProviderRecord record: toRemove){
			records.remove(record);
		    }

		    //If no records are left in the hashset, remove it from the map.
		    if(records.isEmpty()){
			map.remove(name);
		    }
		}
	    }
	    if (map.size() == 0) {
		mProvidersByNamePerUser.remove(userId);
	    }
	}
    }

    //WEIR: Same as done for removeProviderByName
    void removeProviderByClass(ComponentName name, int userId, ProcessRecord proc) {
	if (mSingletonByClass.containsKey(name)) {
	    if (DBG)
		Slog.i(TAG, "Removing from globalByClass name=" + name);
	    //mSingletonByClass.remove(name);
	    if(proc == null){
		mSingletonByClass.remove(name);
	    } else{
		HashSet<ContentProviderRecord> records = mSingletonByClass.get(name);
		HashSet<ContentProviderRecord> toRemove = new HashSet<ContentProviderRecord>();
		if(records!=null) {
		    //Mark records for removal
		    for (ContentProviderRecord record : records){
			if(record==null)
			   continue;
			if(record.proc!=null){
			    if(record.proc.processName.equals(proc.processName)){
				toRemove.add(record);
			    }
			}
		    }

		    //Now remove the records marked for removal
		    for(ContentProviderRecord record: toRemove){
			records.remove(record);
		    }

		    //If no records are left in the hashset, remove it from the map.
		    if(records.isEmpty()){
			mSingletonByClass.remove(name);
		    }
		}
	    }
	} else {
	    if (userId < 0) throw new IllegalArgumentException("Bad user " + userId);
	    if (DBG)
		Slog.i(TAG,
			"Removing from providersByClass name=" + name + " user=" + userId);
	    HashMap<ComponentName, HashSet<ContentProviderRecord>> map = getProvidersByClass(userId);
	    // map returned by getProvidersByClass wouldn't be null
	    //map.remove(name);
	    if(proc == null){
		map.remove(name);
	    } else {
		HashSet<ContentProviderRecord> records = map.get(name);
		HashSet<ContentProviderRecord> toRemove = new HashSet<ContentProviderRecord>();
		if(records!=null){
		    //Mark records for removal
		    for (ContentProviderRecord record : records){
			if(record==null)
			   continue;
			if(record.proc!=null){
			    if(record.proc.processName.equals(proc.processName)){
				toRemove.add(record);
			    }
			}
		    }

		    //Now remove the records marked for removal
		    for(ContentProviderRecord record: toRemove){
			records.remove(record);
		    }

		    //If no records are left in the hashset, remove it from the map.
		    if(records.isEmpty()){
			map.remove(name);
		    }
		}
	    }
	    if (map.size() == 0) {
		mProvidersByClassPerUser.remove(userId);
	    }	
	}
    }

    //WEIR: This method is private, so we just return the entire map.
    private HashMap<String, HashSet<ContentProviderRecord>> getProvidersByName(int userId) {
	if (userId < 0) throw new IllegalArgumentException("Bad user " + userId);
	final HashMap<String, HashSet<ContentProviderRecord>> map = mProvidersByNamePerUser.get(userId);
	if (map == null) {
	    HashMap<String, HashSet<ContentProviderRecord>> newMap = new HashMap<String, HashSet<ContentProviderRecord>>();
	    mProvidersByNamePerUser.put(userId, newMap);
	    return newMap;
	} else {
	    return map;
	}
    }

    //WEIR: Same as done for getProvidersByName
    HashMap<ComponentName, HashSet<ContentProviderRecord>> getProvidersByClass(int userId) {
	if (userId < 0) throw new IllegalArgumentException("Bad user " + userId);
	final HashMap<ComponentName, HashSet<ContentProviderRecord>> map
		= mProvidersByClassPerUser.get(userId);
	if (map == null) {
	    HashMap<ComponentName, HashSet<ContentProviderRecord>> newMap
		    = new HashMap<ComponentName, HashSet<ContentProviderRecord>>();
	    mProvidersByClassPerUser.put(userId, newMap);
	    return newMap;
	} else {
	    return map;
	}
    }

    //WEIR: We need to modify this internal method to accept a hashSet of
    //ContentProviderRecords in the HashMap.  Note that since this method only
    //collects dead providers, we do not need to compare labels.
    private boolean collectForceStopProvidersLocked(String name, int appId,
	    boolean doit, boolean evenPersistent, int userId,
	    HashMap<ComponentName, HashSet<ContentProviderRecord>> providers,
	    ArrayList<ContentProviderRecord> result) {
	boolean didSomething = false;
	for (HashSet<ContentProviderRecord> records : providers.values()) {
	    for(ContentProviderRecord provider: records){
		if ((name == null || provider.info.packageName.equals(name))
			&& (provider.proc == null || evenPersistent || !provider.proc.persistent)) {
		    if (!doit) {
			return true;
		    }
		    didSomething = true;
		    result.add(provider);
		}
	    }
	}
	return didSomething;
    }

    //WEIR: Modifying the implimentation of the private
    //collectForceStopProviders takes care of this
    boolean collectForceStopProviders(String name, int appId,
	    boolean doit, boolean evenPersistent, int userId,
	    ArrayList<ContentProviderRecord> result) {
	boolean didSomething = collectForceStopProvidersLocked(name, appId, doit,
		evenPersistent, userId, mSingletonByClass, result);
	if (!doit && didSomething) {
	    return true;
	}
	if (userId == UserHandle.USER_ALL) {
	    for (int i=0; i<mProvidersByClassPerUser.size(); i++) {
		if (collectForceStopProvidersLocked(name, appId, doit, evenPersistent,
			userId, mProvidersByClassPerUser.valueAt(i), result)) {
		    if (!doit) {
			return true;
		    }
		    didSomething = true;
		}
	    }
	} else {
	    //Modify this hashmap to include a hashset of cprs.
	    HashMap<ComponentName, HashSet<ContentProviderRecord>> items
		    = getProvidersByClass(userId);
	    if (items != null) {
		didSomething |= collectForceStopProvidersLocked(name, appId, doit,
			evenPersistent, userId, items, result);
	    }
	}
	return didSomething;
    }

    //WEIR: 1. Modify this to change ContentProviderRecord references to
    //that of HashSet<ContentProviderRecord>.  
    //2. TODO: Do not dump labeled providers. (We will also not dump labeled services
    //or activities for consistency.
    private boolean dumpProvidersByClassLocked(PrintWriter pw, boolean dumpAll, String dumpPackage,
	    String header, boolean needSep, HashMap<ComponentName, HashSet<ContentProviderRecord>> map) {
	Iterator<Map.Entry<ComponentName, HashSet<ContentProviderRecord>>> it = map.entrySet().iterator();
	boolean written = false;
	while (it.hasNext()) {
	    Map.Entry<ComponentName, HashSet<ContentProviderRecord>> e = it.next();
	    //ContentProviderRecord r = e.getValue();
	    for(ContentProviderRecord r : e.getValue()){
		if (dumpPackage != null && !dumpPackage.equals(r.appInfo.packageName)) {
		    continue;
		}
		if (needSep) {
		    pw.println("");
		    needSep = false;
		}
		if (header != null) {
		    pw.println(header);
		    header = null;
		}
		written = true;
		pw.print("	* ");
		pw.println(r);
		r.dump(pw, "	", dumpAll);
	    }
	}
	return written;
    }

    //WEIR: TODO: Do not dump provides that are labeled
    private boolean dumpProvidersByNameLocked(PrintWriter pw, String
	    dumpPackage, String header, boolean needSep, HashMap<String,
	    HashSet<ContentProviderRecord>> map) {
	Iterator<Map.Entry<String, HashSet<ContentProviderRecord>>> it = map.entrySet().iterator();
	boolean written = false;
	while (it.hasNext()) {
	    Map.Entry<String, HashSet<ContentProviderRecord>> e = it.next();
	    //ContentProviderRecord r = e.getValue();
	    for(ContentProviderRecord r : e.getValue()){
		if (dumpPackage != null && !dumpPackage.equals(r.appInfo.packageName)) {
		   continue;
		}
		if (needSep) {
		    pw.println("");
		    needSep = false;
		}
		if (header != null) {
		   pw.println(header);
		    header = null;
		}
		written = true;
		pw.print("	");
		pw.print(e.getKey());
		pw.print(": ");
		pw.println(r.toShortString());
	    }
	}
	return written;
    }

    //WEIR: Already taken care of in dumpProvidersBy...Locked.
    boolean dumpProvidersLocked(PrintWriter pw, boolean dumpAll, String dumpPackage) {
	boolean needSep = false;

	if (mSingletonByClass.size() > 0) {
	    needSep |= dumpProvidersByClassLocked(pw, dumpAll, dumpPackage,
		    "  Published single-user content providers (by class):", needSep,
		    mSingletonByClass);
	}

	for (int i = 0; i < mProvidersByClassPerUser.size(); i++) {
	    HashMap<ComponentName, HashSet<ContentProviderRecord>> map = mProvidersByClassPerUser.valueAt(i);
	    needSep |= dumpProvidersByClassLocked(pw, dumpAll, dumpPackage,
		    "  Published user " + mProvidersByClassPerUser.keyAt(i)
			    + " content providers (by class):", needSep, map);
	}

	if (dumpAll) {
	    needSep |= dumpProvidersByNameLocked(pw, dumpPackage,
		    "  Single-user authority to provider mappings:", needSep, mSingletonByName);

	    for (int i = 0; i < mProvidersByNamePerUser.size(); i++) {
		needSep |= dumpProvidersByNameLocked(pw, dumpPackage,
			"  User " + mProvidersByNamePerUser.keyAt(i)
				+ " authority to provider mappings:", needSep,
			mProvidersByNamePerUser.valueAt(i));
	    }
	}
	return needSep;
    }

    //WEIR: Modify to account for HashSet<ContentProviderRecord>
    protected boolean dumpProvider(FileDescriptor fd, PrintWriter pw, String name, String[] args,
	    int opti, boolean dumpAll) {
	ArrayList<ContentProviderRecord> allProviders = new ArrayList<ContentProviderRecord>();
	ArrayList<ContentProviderRecord> providers = new ArrayList<ContentProviderRecord>();

	synchronized (mAm) {
	    //allProviders.addAll(mSingletonByClass.values());
	    //WEIR
	    for(HashSet<ContentProviderRecord> records : mSingletonByClass.values()){
		allProviders.addAll(records);
	    }

	    for (int i=0; i<mProvidersByClassPerUser.size(); i++) {
		//allProviders.addAll(mProvidersByClassPerUser.valueAt(i).values());
		//WEIR
		for(HashSet<ContentProviderRecord> records: mProvidersByClassPerUser.valueAt(i).values()){
		    allProviders.addAll(records);
		}
	    }

	    if ("all".equals(name)) {
		providers.addAll(allProviders);
	    } else {
		ComponentName componentName = name != null
			? ComponentName.unflattenFromString(name) : null;
		int objectId = 0;
		if (componentName == null) {
		    // Not a '/' separated full component name; maybe an object ID?
		    try {
			objectId = Integer.parseInt(name, 16);
			name = null;
			componentName = null;
		    } catch (RuntimeException e) {
		    }
		}

		for (int i=0; i<allProviders.size(); i++) {
		    ContentProviderRecord r1 = allProviders.get(i);
		    if (componentName != null) {
			if (r1.name.equals(componentName)) {
			    providers.add(r1);
			}
		    } else if (name != null) {
			if (r1.name.flattenToString().contains(name)) {
			    providers.add(r1);
			}
		    } else if (System.identityHashCode(r1) == objectId) {
			providers.add(r1);
		    }
		}
	    }
	}

	if (providers.size() <= 0) {
	    return false;
	}

	boolean needSep = false;
	for (int i=0; i<providers.size(); i++) {
	    if (needSep) {
		pw.println();
	    }
	    needSep = true;
	    dumpProvider("", fd, pw, providers.get(i), args, dumpAll);
	}
	return true;
    }

    /**
     * Invokes IApplicationThread.dumpProvider() on the thread of the specified provider if
     * there is a thread associated with the provider.
     */
    private void dumpProvider(String prefix, FileDescriptor fd, PrintWriter pw,
	    final ContentProviderRecord r, String[] args, boolean dumpAll) {
	String innerPrefix = prefix + "  ";
	synchronized (mAm) {
	    pw.print(prefix); pw.print("PROVIDER ");
		    pw.print(r);
		    pw.print(" pid=");
		    if (r.proc != null) pw.println(r.proc.pid);
		    else pw.println("(not running)");
	    if (dumpAll) {
		r.dump(pw, innerPrefix, true);
	    }
	}
	if (r.proc != null && r.proc.thread != null) {
	    pw.println("    Client:");
	    pw.flush();
	    try {
		TransferPipe tp = new TransferPipe();
		try {
		    r.proc.thread.dumpProvider(
			    tp.getWriteFd().getFileDescriptor(), r.provider.asBinder(), args);
		    tp.setBufferPrefix("      ");
		    // Short timeout, since blocking here can
		    // deadlock with the application.
		    tp.go(fd, 2000);
		} finally {
		    tp.kill();
		}
	    } catch (IOException ex) {
		pw.println("	  Failure while dumping the provider: " + ex);
	    } catch (RemoteException ex) {
		pw.println("	  Got a RemoteException while dumping the service");
	    }
	}
    }
}
