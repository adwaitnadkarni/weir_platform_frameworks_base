#define LOG_TAG "WeirManagerService-JNI"

//#define LOG_NDEBUG 0

#include "JNIHelp.h"
#include "jni.h"

#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/Log.h>
#include <utils/Log.h>
#include <stdint.h>
#include <string.h>
#include <unistd.h>
#include <stdio.h>
#include <stdlib.h>
#include <fcntl.h>
#include <sys/types.h>
#include <sys/ioctl.h>
#include <errno.h>
#include <sys/mman.h>
#include <android/log.h>
#include <linux/fs.h>
//Constants
#define WEIRIO 'w'

//#define WEIR_HELLO _IOWR(WEIRIO, 0, struct hello)
#define WEIR_GET_PROC_SECLABEL _IOWR(WEIRIO, 1, struct seclabel_struct)
#define WEIR_ADD_GLOBAL_CAP _IOWR(WEIRIO, 2, struct global_cap)
#define WEIR_INIT_PROC_SEC_CONTEXT _IOWR(WEIRIO, 3, struct process_sec_context)
#define WEIR_ADD_TAG_TO_LABEL _IOWR(WEIRIO, 4, struct add_tag_struct)
#define WEIR_ADD_PROCESS_CAP _IOWR(WEIRIO, 5, struct process_cap)

/*IO Datatypes*/
typedef signed long long tag_t;
struct seclabel_struct{
	pid_t pid;
	tag_t *sec;
	int *secsize;
};

struct global_cap{
	tag_t tag;
	int pos; //1=pos, -1=neg, do nothing for 0
	int add; //1=add, -1=rem, do nothing for 0
};

struct process_cap{
	pid_t pid;
	tag_t tag;
	int pos; //1=pos, -1=neg, do nothing for 0
	int add; //1=add, -1=rem, do nothing for 0
};


struct add_tag_struct{
	pid_t pid;
	tag_t tag;
};

struct process_sec_context{
	pid_t pid;
	uid_t uid;
	tag_t* sec;
	tag_t* pos;
	tag_t* neg;
	int secsize;
	int possize;
	int negsize;
};

namespace android {
static jlongArray JNICALL get_process_label_native(JNIEnv *env, jclass, jint pid) {
    jlongArray res = NULL;
    struct seclabel_struct seclabel;
    seclabel.pid=pid;
    seclabel.secsize=(int*)malloc(sizeof(int));
    seclabel.secsize[0]=0;
    int fd = open("/dev/weir", O_RDWR);
    if(fd == -1){
	__android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "get_process_label cannot access /dev/weir\n");
    	return res;
    }

    //Get the size
    int ret = ioctl(fd, WEIR_GET_PROC_SECLABEL, &seclabel);
    //__android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "get_process_label first ioctl succeeds for pid = %d, secsize=%d\n", seclabel.pid, seclabel.secsize[0]);
    if(seclabel.secsize[0]<=0){
	close(fd);
	return res;
    }
    //get the actual label
    seclabel.sec = (tag_t*)malloc(sizeof(tag_t)*seclabel.secsize[0]);
    ret = ioctl(fd, WEIR_GET_PROC_SECLABEL, &seclabel);
    close(fd);

    //Set the result.
    res = env->NewLongArray(seclabel.secsize[0]);
    env->SetLongArrayRegion(res, 0, seclabel.secsize[0], (const jlong*) seclabel.sec);

    if(seclabel.sec!=NULL)  free(seclabel.sec);
    if(seclabel.secsize!=NULL)  free(seclabel.secsize);
    return res;
}

static void JNICALL init_process_security_context_native(JNIEnv *env, jclass, jint pid, jint uid, jlongArray sec, jlongArray pos, jlongArray neg, jint secsize, jint possize, jint negsize){
    struct process_sec_context psec;
    psec.pid = pid;
    psec.uid = uid;
    psec.secsize=0; psec.possize=0; psec.negsize=0;
    psec.sec=NULL; psec.pos=NULL; psec.neg=NULL;
    //Convert sec and assign to psec, if needed
    if(secsize>0){
	psec.secsize = secsize;
	psec.sec = (tag_t*) malloc(sizeof(tag_t) * secsize);
	env->GetLongArrayRegion(sec, 0, secsize, (jlong*)psec.sec);
    }
    //Convert pos and assign to psec, if needed
    if(possize>0){
	psec.possize = possize;
	psec.pos = (tag_t*) malloc(sizeof(tag_t) * possize);
	env->GetLongArrayRegion(pos, 0, possize, (jlong*)psec.pos);
    }
    //Convert neg and assign to psec, if needed
    if(negsize>0){
	psec.negsize = negsize;
	psec.neg = (tag_t*) malloc(sizeof(tag_t) * negsize);
	env->GetLongArrayRegion(neg, 0, negsize, (jlong*)psec.neg);
    }

    //ioctl
    int fd = open("/dev/weir", O_RDWR);
    if(fd == -1){
	__android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "init_process_security_context cannot access /dev/weir\n");
    	return;
    }
    //__android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "init_process_security_context ioctl /dev/weir before call.\n");
    int ret = ioctl(fd, WEIR_INIT_PROC_SEC_CONTEXT, &psec);
    //__android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "init_process_security_context ioctl /dev/weir returned %d\n",ret);

    if(psec.sec!=NULL)	free(psec.sec);
    if(psec.pos!=NULL)	free(psec.pos);
    if(psec.neg!=NULL)	free(psec.neg);
    close(fd);
}

static void JNICALL add_global_cap_native(JNIEnv *env, jclass, jlong tagvalue, jint pos, jint add){
    struct global_cap global;
    global.tag = tagvalue;
    global.pos = pos;
    global.add = add;

    int fd = open("/dev/weir", O_RDWR);
    int ret = ioctl(fd, WEIR_ADD_GLOBAL_CAP, &global);
    close(fd);
}

static void JNICALL add_proc_cap_native(JNIEnv *env, jclass, jint pid, jlong tagvalue, jint pos, jint add){
    struct process_cap proccap;
    proccap.pid = pid;
    proccap.tag = tagvalue;
    proccap.pos = pos;
    proccap.add = add;

    int fd = open("/dev/weir", O_RDWR);
    int ret = ioctl(fd, WEIR_ADD_PROCESS_CAP, &proccap);
}
static void JNICALL add_tag_to_label_native(JNIEnv *env, jclass, jint pid, jlong tagvalue){
    struct add_tag_struct add_tag;
    add_tag.pid = pid;
    add_tag.tag = tagvalue;

    //__android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "add_tag. pid=%d, add_tag.pid=%d, tagvalue=%lld, add_tag.tag_value=%lld\n", pid, add_tag.pid, tagvalue, add_tag.tag);

    int fd = open("/dev/weir", O_RDWR);
    if(fd==-1){
	__android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "add_tag_to_label_native cannot access /dev/weir\n");
    	return;
    }
    //__android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "add_tag_to_label ioctl /dev/weir before call. pid=%d, tagvalue=%lld\n", pid, tagvalue);
    int ret = ioctl(fd, WEIR_ADD_TAG_TO_LABEL, &add_tag);
    close(fd);
    //__android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "add_tag_to_label ioctl /dev/weir returned %d\n",ret);
}

static JNINativeMethod gWeirManagerServiceMethods[] = {
    /* name, signature, funcPtr */
    { "get_process_label_native", "(I)[J", (void*) get_process_label_native },
    { "init_process_security_context_native", "(II[J[J[JIII)V", (void*) init_process_security_context_native },
    { "add_global_cap_native", "(JII)V", (void*) add_global_cap_native },
    { "add_tag_to_label_native", "(IJ)V", (void*) add_tag_to_label_native},
    { "add_proc_cap_native", "(IJII)V", (void*) add_proc_cap_native}};

int register_android_server_WeirManagerService(JNIEnv* env) {
    //int res = jniRegisterNativeMethods(env, "com/android/server/weir/WeirManagerService",
    //        gWeirManagerServiceMethods, NELEM(gWeirManagerServiceMethods));
    int res = AndroidRuntime::registerNativeMethods(env, "com/android/server/weir/WeirManagerService",
            gWeirManagerServiceMethods, NELEM(gWeirManagerServiceMethods));

    LOG_FATAL_IF(res < 0, "Unable to register native methods.");
    return 0;
}

} /* namespace android */
