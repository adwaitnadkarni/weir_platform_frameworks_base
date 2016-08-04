package android.os.weir;

import java.util.HashSet;
import java.util.ArrayList;
/**
 * Weir manager local system service interface.
 *
 * @hide Only for use within the system server.
 */
public abstract class WeirManagerInternal {
    public abstract void systemHello();
    public abstract String getWeirProcSuffix();
    public abstract HashSet<Long> getProcessLabel(int pid);
    public abstract void initProcessSecurityContext(int pid, int uid, HashSet<Long> secrecyLabel);
    public abstract ArrayList<String> getExistingProcesses(String processIndex);
    public abstract String allocateProcessName(String processIndex, String processName);
    public abstract String getDirectoryPrefix(String packageName, HashSet<Long> label);
    public abstract String getExternalDirectory(HashSet<Long> label);
    public abstract HashSet<Long> checkIntentCallerLabel(int callerUid, HashSet<Long> callerLabel, HashSet<String> intentLabel);
}
