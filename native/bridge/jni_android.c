// SPDX-License-Identifier: GPL-3.0-or-later
#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <stdint.h>
#include "callbacks.h"

extern char *GCCStart(char *data, int size, char *home, int fd);
extern void GCCStop(void);
extern char *GCCVersion(void);
extern void GCCFree(void *ptr);

static JavaVM *java_vm;
static pthread_mutex_t lifecycle_lock = PTHREAD_MUTEX_INITIALIZER;
static jobject protector_object;
static jmethodID protect_method;
static int running;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    java_vm = vm;
    return JNI_VERSION_1_6;
}

static int protect_from_go(int fd, void *context) {
    (void)context;
    if (!java_vm || !protector_object || !protect_method) return 0;
    JNIEnv *env = NULL;
    int attached = 0;
    jint state = (*java_vm)->GetEnv(java_vm, (void **)&env, JNI_VERSION_1_6);
    if (state == JNI_EDETACHED) {
        if ((*java_vm)->AttachCurrentThread(java_vm, &env, NULL) != JNI_OK) return 0;
        attached = 1;
    } else if (state != JNI_OK) return 0;
    jboolean ok = (*env)->CallBooleanMethod(env, protector_object, protect_method, (jint)fd);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); ok = JNI_FALSE; }
    if (attached) (*java_vm)->DetachCurrentThread(java_vm);
    return ok == JNI_TRUE;
}

// Caller holds lifecycle_lock. Clearing the callback waits for any Go invocation to
// finish before deleting the JNI global reference it uses.
static void release_protector(JNIEnv *env) {
    gcc_set_protector(NULL, NULL);
    if (protector_object) (*env)->DeleteGlobalRef(env, protector_object);
    protector_object = NULL;
    protect_method = NULL;
}
static jstring diagnostic(JNIEnv *env, const char *code) {
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    return (*env)->NewStringUTF(env, code);
}

JNIEXPORT jstring JNICALL
Java_com_gccwebos_android_vpn_MihomoNative_nativeStart(
    JNIEnv *env, jclass cls, jstring config, jstring home, jint fd, jobject protector) {
    (void)cls;
    pthread_mutex_lock(&lifecycle_lock);
    jstring result = NULL;
    const char *home_chars = NULL;
    jbyte *config_bytes = NULL;
    jbyteArray encoded = NULL;
    char *native_error = NULL;
    if (running) { result = diagnostic(env, "PROCESS_RESTART_REQUIRED"); goto out; }
    if (!config || !home || !protector || fd < 0) { result = diagnostic(env, "INVALID_ARGUMENT"); goto out; }
    if ((*env)->GetStringLength(env, config) > 65536 || (*env)->GetStringLength(env, home) > 4096) {
        result = diagnostic(env, "CONFIG_REJECTED"); goto out;
    }
    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    if (!string_class) { result = diagnostic(env, "JNI_FAILED"); goto out; }
    jmethodID get_bytes = (*env)->GetMethodID(env, string_class, "getBytes", "(Ljava/lang/String;)[B");
    jstring utf8 = (*env)->NewStringUTF(env, "UTF-8");
    if (!get_bytes || !utf8) { (*env)->DeleteLocalRef(env, string_class); result = diagnostic(env, "JNI_FAILED"); goto out; }
    encoded = (jbyteArray)(*env)->CallObjectMethod(env, config, get_bytes, utf8);
    (*env)->DeleteLocalRef(env, utf8);
    (*env)->DeleteLocalRef(env, string_class);
    if ((*env)->ExceptionCheck(env) || !encoded) { result = diagnostic(env, "JNI_FAILED"); goto out; }
    jsize length = (*env)->GetArrayLength(env, encoded);
    if (length < 1 || length > 65536) { result = diagnostic(env, "CONFIG_REJECTED"); goto out; }
    jclass protector_class = (*env)->GetObjectClass(env, protector);
    if (!protector_class) { result = diagnostic(env, "JNI_FAILED"); goto out; }
    protect_method = (*env)->GetMethodID(env, protector_class, "protectSocket", "(I)Z");
    (*env)->DeleteLocalRef(env, protector_class);
    if (!protect_method) { result = diagnostic(env, "PROTECTOR_MISSING"); goto out; }
    protector_object = (*env)->NewGlobalRef(env, protector);
    if (!protector_object) { result = diagnostic(env, "JNI_FAILED"); goto out; }
    gcc_set_protector(protect_from_go, NULL);
    config_bytes = (*env)->GetByteArrayElements(env, encoded, NULL);
    home_chars = (*env)->GetStringUTFChars(env, home, NULL);
    if (!config_bytes || !home_chars) { result = diagnostic(env, "JNI_FAILED"); goto out; }
    native_error = GCCStart((char *)config_bytes, length, (char *)home_chars, fd);
    if (!native_error || *native_error) {
        result = diagnostic(env, native_error ? native_error : "NATIVE_START_FAILED");
    } else {
        running = 1;
    }
out:
    if (native_error) GCCFree(native_error);
    if (home_chars) (*env)->ReleaseStringUTFChars(env, home, home_chars);
    if (config_bytes) (*env)->ReleaseByteArrayElements(env, encoded, config_bytes, JNI_ABORT);
    if (encoded) (*env)->DeleteLocalRef(env, encoded);
    if (!running) release_protector(env);
    pthread_mutex_unlock(&lifecycle_lock);
    return result;
}

JNIEXPORT void JNICALL
Java_com_gccwebos_android_vpn_MihomoNative_nativeStop(JNIEnv *env, jclass cls) {
    (void)cls;
    pthread_mutex_lock(&lifecycle_lock);
    GCCStop();
    running = 0;
    release_protector(env);
    pthread_mutex_unlock(&lifecycle_lock);
}
JNIEXPORT jstring JNICALL
Java_com_gccwebos_android_vpn_MihomoNative_nativeVersion(JNIEnv *env, jclass cls) {
    (void)cls;
    char *version = GCCVersion();
    if (!version) return diagnostic(env, "UNAVAILABLE");
    jstring result = (*env)->NewStringUTF(env, version);
    GCCFree(version);
    return result;
}
