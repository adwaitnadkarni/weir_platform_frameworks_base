package com.android.server.weir;

import com.android.server.SystemService;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.weir.IWeirManager;
import android.os.weir.WeirLabelOps;
import android.os.Looper;
import android.os.weir.WeirManager;
import android.os.weir.WeirManagerInternal;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.util.Slog;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Arrays;
import com.android.internal.app.IAppOpsService;
import java.net.InetAddress;
import java.util.Random;
import java.lang.StringBuilder;
 
/**
 * The weir manager service for managing the Weir DIFC system
 * 
 */
public final class WeirManagerService extends SystemService {
    private static final String TAG = "WeirManagerService";
    private final Context mContext;
    private WeirHelper mHelper;
    private WeirDomainSocketInterface mSocketInterface = null;
    private WeirDomainSocketInterface_DNS mSocketInterface_DNS = null;
    private static final int WEIR_MGR_ALLOW = 0;
    private static final int WEIR_MGR_DENY = 1;
    //Switching off random directories for debugging.
    private static final boolean RANDOM_DIRS=false;
    
    /* Maps to hold tagname, value, caps, etc.*/
    //All the tag values created
    static HashSet<Long> tagValues = new HashSet<Long>();
    //tagName :: tagValue
    static HashMap<String, Long> tagStore = new HashMap<String, Long>();
    //poscapmap - packagename -> hashset of tags
    static HashMap<String, HashSet<String>> posCapMap = new HashMap<String, HashSet<String>>();
    //negcapmap - packagename -> hashset of tags
    static HashMap<String, HashSet<String>> negCapMap = new HashMap<String, HashSet<String>>();
    //global pos and neg caps
    static HashSet<String> globalPosSet = new HashSet<String>();
    static HashSet<String> globalNegSet = new HashSet<String>();

    //Weir processNames; for each uid:processName pair, we allocate a new processName=processName+"wproc_"+procCount. 
    //For now, the procCount is the size of the arraylist, as we don't delete any allocated processNames.
    //TODO: Use HashSet instead of ArrayList, and use a separate counter per list for allocating new processNames
    static HashMap<String, ArrayList<String>> existingProcesses = new HashMap<String, ArrayList<String>>();
    static final String wproc="_wproc_";
    static final String wdir="wdir_";

    //HashMap for directories - packageName : ArrayList<Labels>
    //The index of the label in the arrayList is its directory index.
    //New labels get allocated the latest available index, i.e., the size
    static HashMap<String, ArrayList<HashSet<Long>>> directoryMap = new HashMap<String, ArrayList<HashSet<Long>>>();
    static HashMap<String, ArrayList<String>> directoryMapRandom = new HashMap<String, ArrayList<String>>();
    //List of label specific external storage weir postfixes.
    //The label is stored in the list, and the index indicates the postfix
    static ArrayList<HashSet<Long>> externalDirectoryList = new ArrayList<HashSet<Long>>();
    static ArrayList<String> externalDirectoryListRandom = new ArrayList<String>();
    static HashSet<String> randomDirNames = new HashSet<String>();
    
    //TagValue -> Domain(s) map
    static HashMap<Long, HashSet<String>> tagDomainMap = new HashMap<Long, HashSet<String>>();
    
    //IPMap
    static HashMap<String, String> IPMap = new HashMap<String, String>();
    //
    /* Native declarations */
    private static native long[] get_process_label_native(int pid);
    private static native void init_process_security_context_native(int pid, int uid, long[] sec, long[] pos, long[] neg, int secsize, int possize, int negsize);
    private static native void add_global_cap_native(long tagValue, int pos, int add);
    private static native void add_proc_cap_native(int pid, long tagValue, int pos, int add);
    private static native void add_tag_to_label_native(int pid, long tagValue);

    public WeirManagerService(Context context) {
	super(context);
	mContext = context;
	mHelper = new WeirHelper(context);
	startDomainSocketInterface();
	Log.i(TAG, "Initialized.");
    }

    public void startDomainSocketInterface() {
	mSocketInterface = new WeirDomainSocketInterface(this);
	mSocketInterface_DNS = new WeirDomainSocketInterface_DNS(this);
	new Thread(mSocketInterface).start();
	new Thread(mSocketInterface_DNS).start();
    } 

    @Override
    public void onStart() {
	publishBinderService(Context.WEIR_SERVICE, new BinderService());
	publishLocalService(WeirManagerInternal.class, new LocalService());
	Log.i(TAG, "Started.");
    }

    public void systemReady(IAppOpsService appOps) {
	    Log.i(TAG, "Received System Ready.");
    }

    /**
     * Handler for asynchronous operations performed by the power manager.
     */

    private final class BinderService extends IWeirManager.Stub {
	@Override // Binder call
	public void hello() {
	    Log.i(TAG, "Hello!");
	}

	@Override // Create tag
	public void createTag(String tagName, boolean globalPos, boolean globalNeg, String[] posCapAlloc, String[] negCapAlloc, String[] domains) {
	    int uid = Binder.getCallingUid();
	    String packageName = mHelper.getPackageName(uid);
	    //return if no packagename
	    if(packageName==null)
		return;
	    createTagInternal(tagName, packageName, Binder.getCallingPid(), globalPos, globalNeg, posCapAlloc, negCapAlloc, domains);
	}

	@Override // add tag to label
	public void addTagToLabel(String ownerPackageName, String tagName) {
	    int callerUid = Binder.getCallingUid();
	    int callerPid = Binder.getCallingPid();
	    String callerPackageName = mHelper.getPackageName(callerUid);
	    //return if no packagename
	    if(callerPackageName==null)
		return;
	    String absoluteTagName = ownerPackageName+":"+tagName;
	    addTagToLabelInternal(absoluteTagName, callerPackageName, callerPid);
	}

	@Override // check if the tag can be added to the label
	public boolean checkAdd(String ownerPackageName, String tagName) {
	    int callerUid = Binder.getCallingUid();
	    String callerPackageName = mHelper.getPackageName(callerUid);
	    //return if no packagename
	    if(callerPackageName==null)
		return false;
	    String absoluteTagName = ownerPackageName+":"+tagName;
	    return checkAddInternal(absoluteTagName, callerPackageName);
	}
    }

    private final class LocalService extends WeirManagerInternal {

	@Override
	public void systemHello() {
	    Log.i(TAG, "SystemHello!");
	}
	
	@Override
	public String getWeirProcSuffix() {
	    return wproc;
	}

	@Override
	public HashSet<Long> getProcessLabel(int pid) {
	    //Debug
	    //Log.i(TAG, "getProcessLabel called for pid="+pid);
	    //
	    //If pid <=0, its an empty label, we don't need to ask the kernel
	    if(pid <=0){
		return new HashSet<Long>();
	    }
	    long[] label = get_process_label_native(pid);
	    int length = label==null?0:label.length;
	    HashSet<Long> processLabel = primitiveArrayToSet(label, length);
	    //Debug
	    //Log.i(TAG, "getProcessLabel for pid="+pid+"; label="+processLabel);
	    //
	    return processLabel;
	}
	@Override
	public void initProcessSecurityContext(int pid, int uid, HashSet<Long> secrecyLabel){
	    String packageName = mHelper.getPackageName(uid);
	    if(packageName==null)
		return;
	    long[] sec; long[] pos; long[] neg;
	    int secsize=0, possize=0, negsize=0;

	    //convert secrecyLabel to sec, if size >0
	    sec = setToPrimitiveArray(secrecyLabel);
	    if(sec!=null)   secsize = sec.length;

	    synchronized(this){
		//get poscaps and negcaps, convert to pos and neg
		pos = setToPrimitiveArray(nameToValueSet(posCapMap.get(packageName)));
		if(pos!=null)	possize = pos.length;
		neg = setToPrimitiveArray(nameToValueSet(negCapMap.get(packageName)));
		if(neg!=null)	negsize = neg.length;
	    }

	    //Call native
	    //Debug
	    //Log.i(TAG, "init process security for pid="+pid+"; secsize="+secsize+"; possize="+possize+"; negsize="+negsize);
	    init_process_security_context_native(pid, uid, sec, pos, neg, secsize, possize, negsize);
	    //Log.i(TAG, "init process security for pid="+pid+" returned successfully.");

	    //DEBUG
	    //Verify that the init took place
	    //Log.i(TAG, "get processLabel after init.");
	    //getProcessLabel(pid);
	}
	@Override
	public ArrayList<String> getExistingProcesses(String processIndex){
	    //processIndex = uid+":"+processName, created by the client when allocating new processes.
	    return existingProcesses.get(processIndex); 
	}
	@Override
	public String allocateProcessName(String processIndex, String processName){
	    int count=0;
	    Log.i(TAG, "allocateProcessName called for index:"+processIndex+"; processName:"+processName);
	    ArrayList<String> procList = existingProcesses.get(processIndex);
	    if(procList==null){
		Log.i(TAG, "allocateProcessName: procList not found for index:"+processIndex+"; processName:"+processName);
		procList = new ArrayList<String>();
	    } else {
		count = procList.size();
	    }
	    String newProcessName = processName+wproc+count;
	    procList.add(newProcessName);
	    existingProcesses.put(processIndex, procList);
	    return newProcessName;
	}
	@Override
	public String getDirectoryPrefix(String packageName, HashSet<Long> label){
	    String weir_prefix = wdir;
	    int wIndex = 0;
	    ArrayList<HashSet<Long>> labelList = directoryMap.get(packageName);
	    if(labelList == null){
		labelList = new ArrayList<HashSet<Long>>();
		labelList.add(label);
		directoryMap.put(packageName, labelList);
		//wIndex stays 0, as we want it to start from 0

		if(RANDOM_DIRS){
		    ArrayList<String> nameList = new ArrayList<String>();
		    nameList.add(getRandomDirectoryName());
		    directoryMapRandom.put(packageName, nameList);
		}

	    } else{
		//labelList must have at least one entry, else it would not have existed.
		wIndex = labelList.size();//assume that no label in the list matches, and you have to allocate a new one.
		for(int i=0; i < labelList.size(); i++){
		    if(WeirLabelOps.equals(labelList.get(i), label)){
			wIndex = i;
			break;
		    }
		}
		//If you  did not find a match, then add the new label to the list; the index has already been allocated.
		if(wIndex == labelList.size()){
		    labelList.add(label);
		    if(RANDOM_DIRS){
			ArrayList<String> nameList = directoryMapRandom.get(packageName);
			nameList.add(getRandomDirectoryName());
			directoryMapRandom.put(packageName, nameList);
		    }
		}
	    }
	    //Returns serial # for debugging of prototype; can return random instead.
	    if(RANDOM_DIRS){
		return "/" + weir_prefix + directoryMapRandom.get(packageName).get(wIndex); 
	    }
	    return "/" + weir_prefix + wIndex;
	}

	@Override
	public String getExternalDirectory(HashSet<Long> label){
	    String weir_postfix = wdir;
	    int wIndex = 0;
	    if(externalDirectoryList == null){
		externalDirectoryList = new ArrayList<HashSet<Long>>();
		externalDirectoryList.add(label);

		if(RANDOM_DIRS){
		    externalDirectoryListRandom = new ArrayList<String>();
		    externalDirectoryListRandom.add(getRandomDirectoryName());
		}
		//wIndex stays 0, as we want it to start from 0
	    } else{
		//labelList must have at least one entry, else it would not have existed.
		wIndex = externalDirectoryList.size();//assume that no label in the list matches, and you have to allocate a new one.
		for(int i=0; i < externalDirectoryList.size(); i++){
		    if(WeirLabelOps.equals(externalDirectoryList.get(i), label)){
			wIndex = i;
			break;
		    }
		}
		//If you  did not find a match, then add the new label to the list; the index has already been allocated.
		if(wIndex == externalDirectoryList.size()){
		    externalDirectoryList.add(label);
		    if(RANDOM_DIRS){
			externalDirectoryListRandom.add(getRandomDirectoryName());
		    }
		}
	    }

	    //Returns serial # for debugging of prototype; can return random instead.
	    if(RANDOM_DIRS){
		return weir_postfix + externalDirectoryListRandom.get(wIndex); 
	    }
	    return weir_postfix + wIndex;
	}


	@Override
	public HashSet<Long> checkIntentCallerLabel(int callerUid, HashSet<Long> callerLabel, HashSet<String> intentLabel){
	    //NOTE: The intentLabel contains absoluteTagNames
	    //Log.i(TAG, "checkIntentCallerLabel: callerUid:"+callerUid+"; callerLabel:"+callerLabel+"; intentLabel:"+intentLabel);

	    String callerPackageName = mHelper.getPackageName(callerUid);
	    synchronized(this){
		HashSet<String> realCallerLabel = new HashSet<String>();
		for(Long tagValue: callerLabel){
		    String tagName = tagNameForValueLocked(tagValue);
		    if(tagName == null){
			//Log.i(TAG, "Malformed callerLabel, returning callerLabel:"+callerLabel);		    
			return callerLabel;
		    }
		    realCallerLabel.add(tagName);
		}
		//For every tag in realCallerLabel and not in intentLabel,
		//callerPackageName must have the neg capability.
		for(String tagName : realCallerLabel){
		    if(!intentLabel.contains(tagName)){
			if(globalNegSet.contains(tagName) || 
			    (negCapMap.containsKey(callerPackageName) && 
				negCapMap.get(callerPackageName).contains(tagName))){
			    //check passes, do nothing
			} else{
			    //cannot change, return callerLabel
			    //Log.i(TAG, "Failed check for negcap. returning callerLabel:"+callerLabel);
			    return callerLabel;
			}
		    }
		}
		

		//For every tag in intentLabel and not in realCallerLabel, 
		//callerUid must have the pos capability.
		for(String tagName : intentLabel){
		    if(!realCallerLabel.contains(tagName)){
			if(globalPosSet.contains(tagName) || 
			    (posCapMap.containsKey(callerPackageName) && 
				posCapMap.get(callerPackageName).contains(tagName))){
			    //check passes, do nothing
			} else{
			    //cannot change, return callerLabel
			    //Log.i(TAG, "Failed check for poscap. returning callerLabel:"+callerLabel);
			    return callerLabel;
			}
		    }
		}

		//All checks succeed. Convert and return the intentLabel
		HashSet<Long> realIntentLabel = new HashSet<Long>();
		for(String tagName: intentLabel){
		    if(!tagStore.containsKey(tagName)){
			//Log.i(TAG, "Malformed intentLabel, returning callerLabel:"+callerLabel);
			return callerLabel;
		    }
		    realIntentLabel.add(tagStore.get(tagName));
		}
		return realIntentLabel;
	    }
	}
    }
    //Internal methods
    private void createTagInternal(String tagName, String packageName, int ownerPid, boolean globalPos, boolean globalNeg, String[] posCapAlloc, String[] negCapAlloc, String[] domains) {
	String absoluteTagName = packageName+":"+tagName;
	//return if tag exists
	if(tagStore.containsKey(absoluteTagName))
	    return;

	long tagValue;
	synchronized(this){
	    //Get a new, unique tagValue
	    tagValue = mHelper.getRandomTag();
	    while(tagValues.contains(tagValue)){
		tagValue = mHelper.getRandomTag();
	    }
	
	    //Insert the tagValue into the set.
	    tagValues.add(tagValue);

	    //Update the tagname-value map
	    tagStore.put(absoluteTagName, tagValue);

	    //give the owner poscaps and negcaps for the tag
	    addPosCapForPackage(packageName, absoluteTagName);
	    addNegCapForPackage(packageName, absoluteTagName);

	    //Update the owner process's poscaps and negcaps in the kernel
	    //int pid = Binder.getCallingPid();
	    add_proc_cap_native(ownerPid, tagValue, 1, 1);//pos, add
	    add_proc_cap_native(ownerPid, tagValue, -1, 1);//neg, add
	    
	    if(globalPos){
		globalPosSet.add(absoluteTagName);
		add_global_cap_native(tagValue, 1, 1);//pos = 1, add = 1
	    }
	    if(globalNeg){
		globalNegSet.add(absoluteTagName);
		add_global_cap_native(tagValue, -1, 1);//neg = -1, add = 1
	    }

	    //Add caps as assigned by the owner. The packages to which caps
	    //are assigned may not be installed yet.
	    if(posCapAlloc != null){
		for(int i =0; i<posCapAlloc.length; i++){
		    addPosCapForPackage(posCapAlloc[i], absoluteTagName);
		}
	    }
	    if(negCapAlloc != null){
		for(int i =0; i<negCapAlloc.length; i++){
		    addNegCapForPackage(negCapAlloc[i], absoluteTagName);
		}
	    }

	    //Lastly, allocate domains supplied, if any, for the tagvalue
	    if(domains!=null){
		List<String> list = Arrays.asList(domains);
		HashSet<String> domainSet = new HashSet<String>(list);
		tagDomainMap.put(tagValue, domainSet);
	    }
	    //Debug
	    //Log.d(TAG, "Tag created for owner="+packageName+"; tagName="+tagName+"; tagValue="+tagValue);
	}
    }

    boolean checkAddInternal(String tagName, String packageName) {
	synchronized(this){
	    return checkAddInternalLocked(tagName, packageName);
	}
    }

    boolean checkAddInternalLocked(String tagName, String packageName) {
	boolean canAdd = false;
	if(!tagStore.containsKey(tagName))
	    return canAdd;
	if(globalPosSet.contains(tagName) || (posCapMap.containsKey(packageName) && posCapMap.get(packageName).contains(tagName))){
	    canAdd = true;
	}
	return canAdd;
    }

    void addTagToLabelInternal(String tagName, String packageName, int pid){
	synchronized(this){
	    //First, check pos cap, and global caps.
	    boolean canAdd = checkAddInternalLocked(tagName, packageName);
	    if(!canAdd)
		return;
	    //Debug
	    Log.d(TAG, "Adding tag for caller="+packageName+"; pid = "+pid+ "; absTagName="+tagName+"; tagValue="+tagStore.get(tagName).longValue());
	    //
	    add_tag_to_label_native(pid, tagStore.get(tagName).longValue());
	    //Debug
	    Log.d(TAG, "Tag added for caller="+packageName+"; absTagName="+tagName+"; tagValue="+tagStore.get(tagName).longValue());
	    //
	}
    }

    private void addPosCapForPackage(String packageName, String tagName){
	HashSet<String> capSet = posCapMap.get(packageName);
	if(capSet == null){
	   capSet = new HashSet<String>();
	}
	capSet.add(tagName);
	posCapMap.put(packageName, capSet);   
    }
    private void addNegCapForPackage(String packageName, String tagName){
	HashSet<String> capSet = negCapMap.get(packageName);
	if(capSet == null){
	   capSet = new HashSet<String>();
	}
	capSet.add(tagName);
	negCapMap.put(packageName, capSet);   
    }

    //converts tagname sets to tagvalue sets
    private HashSet<Long> nameToValueSet(HashSet<String> nameSet){
	HashSet<Long> retSet = new HashSet<Long>();
	if(nameSet==null || nameSet.isEmpty()){
	    return retSet;
	}

	for (String tagName: nameSet){
	    if(tagStore.containsKey(tagName)){
		retSet.add(tagStore.get(tagName));
	    }
	}
	return retSet;
    }
    //returns null for empty sets
    private long[] setToPrimitiveArray(HashSet<Long> setA){
	long[] retArray = null;
	if(setA == null || setA.isEmpty())
	    return retArray;

	retArray = new long[setA.size()];
	int i=0;
	for (Long a : setA){
	    retArray[i]=a;
	    i++;
	}
	return retArray;
    }
    //returns null for null arrays
    private HashSet<Long> primitiveArrayToSet(long[] arrayA, int size){
	HashSet<Long> retSet = new HashSet<Long>();
	if(size<=0)
	    return retSet;

	for (int i=0; i<size; i++){
	    retSet.add(arrayA[i]);
	}
	return retSet;
    }

    //get tagName from tagValue
    String tagNameForValueLocked(Long tagValue){
	for(String tagName: tagStore.keySet()){
	    if(tagValue.equals(tagStore.get(tagName))){
		return tagName;
	    }
	}
	return null;
    }

    String getRandomDirectoryName(){
	String ret=""+randomString(8);
	while(randomDirNames.contains(ret)){
	    ret=""+randomString(8);
	}

	randomDirNames.add(ret);
	return ret;
    }

    String randomString(int length)
    {
	Random random = new Random();
	String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890";
	StringBuilder sb = new StringBuilder();
	for (int i = 0; i < length; i++) {
	    sb.append(chars.charAt(random.nextInt(chars.length())));
	}
	return sb.toString();
    }

    /*************** Domain Socket Query ***********************/
    private int socketQueryInternal(String ipAddress, String labelString){
	int decision = WEIR_MGR_ALLOW;
	try{
	    InetAddress inetAddr = InetAddress.getByName(ipAddress);
	    String domainHost = IPMap.get(inetAddr.getHostAddress());
	    Log.i(TAG, "Domain hostname = "+domainHost+"; labelString="+labelString+"; IP="+ipAddress);
	
	    //Perform check, for each label
	    String tags[] = labelString.split("#");

	    Log.i(TAG, "Tags to be potentially declassified:"+tags+"; count="+tags.length);
	    if(tags==null || tags.length==0){
		//Something wrong; the upcall should not have been made
		//return deny
		Log.i(TAG, "Empty TagList at socketQuery?");
		decision = WEIR_MGR_DENY;
		return decision;
	    }
	    for(int i =0; i < tags.length; i++){
		try{
		    Log.i(TAG, "Checking for tag:"+tags[i]);
		    Long tagLong = Long.parseLong(tags[i]);
		    //TODO: May need to look for top level domains; We are
		    //currently looking for exact domains. May be subdomains
		    //can be specified in the policy.
		    Log.i(TAG, "Checking for long tag:"+tagLong);
		    if(!tagDomainMap.containsKey(tagLong) ||
			    !tagDomainMap.get(tagLong).contains(domainHost)){

			Log.i(TAG, "Deny for tag="+tagLong);
			decision = WEIR_MGR_DENY;
			break;
		    }
		} catch(Exception e){
		    //For exceptions, default deny.
		    Log.i(TAG, "Exception while checking for tag:"+tags[i]);
		    e.printStackTrace();
		    //decision = WEIR_MGR_DENY;
		    //break;
		}
	    }
	} catch (Exception e){
		Log.i(TAG, "Exception splitting the labelString.");
		e.printStackTrace();
	    //For exceptions, default deny.
	    //decision = WEIR_MGR_DENY;
	}
	return decision;
    }
    public String query(String val) {
	//Log.i(TAG, "QUERY: "+val);
	//default deny
	int decision = WEIR_MGR_DENY;
	/*Message Format:
	 *  For socket: socket;ip address (v4/v6);uid;pid;label;
	 */
    
	//Get the attributes
	String upcallAttrs[] = val.split(";");
	if(upcallAttrs.length<5){
	    //For errors, allow
	    return ""+WEIR_MGR_ALLOW;
	}
	//Call for a decision
	//Treating both socket hooks as the same for now.
	if(upcallAttrs[0].startsWith("socket")){
	    decision = socketQueryInternal(upcallAttrs[1], upcallAttrs[4]);
	}

	return ""+decision;
    }

    public void query_DNS(String val) {
	//Log.i(TAG, val);
	//Get the attributes
	String upcallAttrs[] = val.split(";");
	if (upcallAttrs!=null && upcallAttrs[0].equals("getaddrinfo")){
	    //Log.i(TAG, val);
	}
	if(upcallAttrs.length>=3){
	    IPMap.put(upcallAttrs[2], upcallAttrs[1]);
	}
    }
}
