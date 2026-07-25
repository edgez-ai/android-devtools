#include <dlfcn.h>
#include <jni.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>

typedef char *(*create_identity_fn)(const char *);
typedef char *(*start_client_fn)(const char *);
typedef char *(*stop_client_fn)(void);
typedef char *(*pair_wireless_fn)(const char *, int, const char *, const char *, const char *, int);
typedef char *(*set_adb_target_fn)(const char *, int);
typedef void (*free_result_fn)(char *);
typedef void (*adb_unreachable_cb)(void);
typedef void (*register_adb_unreachable_fn)(adb_unreachable_cb);

static JavaVM *g_vm;
static jclass g_bridge_class;
static jmethodID g_adb_unreachable_method;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;
    g_vm = vm;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass local = (*env)->FindClass(env, "ai/edgez/androiddevtools/NativeBridge");
    if (local != NULL) {
        g_bridge_class = (*env)->NewGlobalRef(env, local);
        (*env)->DeleteLocalRef(env, local);
        g_adb_unreachable_method = (*env)->GetStaticMethodID(
                env, g_bridge_class, "onAdbUnreachable", "()V");
    }
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }
    return JNI_VERSION_1_6;
}

static void on_adb_unreachable(void) {
    if (g_vm == NULL || g_bridge_class == NULL || g_adb_unreachable_method == NULL) {
        return;
    }
    JNIEnv *env = NULL;
    bool attached = false;
    jint state = (*g_vm)->GetEnv(g_vm, (void **) &env, JNI_VERSION_1_6);
    if (state == JNI_EDETACHED) {
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK) {
            return;
        }
        attached = true;
    } else if (state != JNI_OK) {
        return;
    }
    (*env)->CallStaticVoidMethod(env, g_bridge_class, g_adb_unreachable_method);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }
    if (attached) {
        (*g_vm)->DetachCurrentThread(g_vm);
    }
}

static jstring error_result(JNIEnv *env, const char *message) {
    return (*env)->NewStringUTF(env, message);
}

static void *open_library(void) {
    return dlopen("libedgejoin.so", RTLD_NOW);
}

static jstring copy_result(JNIEnv *env, char *result, free_result_fn free_result) {
    if (result == NULL) {
        return error_result(env, "{\"ok\":false,\"status_code\":0,\"error\":\"native response is null\"}");
    }
    jstring output = (*env)->NewStringUTF(env, result);
    free_result(result);
    return output;
}

JNIEXPORT jstring JNICALL
Java_ai_edgez_androiddevtools_NativeBridge_nativeCreateIdentity(
        JNIEnv *env, jclass clazz, jstring name) {
    (void) clazz;
    void *handle = open_library();
    if (handle == NULL) {
        return error_result(env, "{\"ok\":false,\"status_code\":0,\"error\":\"failed to load libedgejoin.so\"}");
    }
    create_identity_fn create_identity = (create_identity_fn) dlsym(handle, "EdgeCreateIdentity");
    free_result_fn free_result = (free_result_fn) dlsym(handle, "EdgeJoinFree");
    if (create_identity == NULL || free_result == NULL) {
        dlclose(handle);
        return error_result(env, "{\"ok\":false,\"status_code\":0,\"error\":\"missing identity symbols\"}");
    }
    const char *value = (*env)->GetStringUTFChars(env, name, NULL);
    char *result = create_identity(value);
    (*env)->ReleaseStringUTFChars(env, name, value);
    jstring output = copy_result(env, result, free_result);
    dlclose(handle);
    return output;
}

JNIEXPORT jstring JNICALL
Java_ai_edgez_androiddevtools_NativeBridge_nativeStartClient(
        JNIEnv *env, jclass clazz, jstring config) {
    (void) clazz;
    void *handle = open_library();
    if (handle == NULL) {
        return error_result(env, "{\"ok\":false,\"status_code\":0,\"error\":\"failed to load libedgejoin.so\"}");
    }
    start_client_fn start_client = (start_client_fn) dlsym(handle, "EdgeStartClient");
    free_result_fn free_result = (free_result_fn) dlsym(handle, "EdgeJoinFree");
    register_adb_unreachable_fn register_callback =
            (register_adb_unreachable_fn) dlsym(handle, "EdgeRegisterAdbUnreachableCallback");
    if (start_client == NULL || free_result == NULL) {
        dlclose(handle);
        return error_result(env, "{\"ok\":false,\"status_code\":0,\"error\":\"missing start symbols\"}");
    }
    if (register_callback != NULL) {
        register_callback(&on_adb_unreachable);
    }
    const char *value = (*env)->GetStringUTFChars(env, config, NULL);
    char *result = start_client(value);
    (*env)->ReleaseStringUTFChars(env, config, value);
    jstring output = copy_result(env, result, free_result);
    dlclose(handle);
    return output;
}

JNIEXPORT jstring JNICALL
Java_ai_edgez_androiddevtools_NativeBridge_nativeStopClient(
        JNIEnv *env, jclass clazz) {
    (void) clazz;
    void *handle = open_library();
    if (handle == NULL) {
        return error_result(env, "{\"ok\":false,\"status_code\":0,\"error\":\"failed to load libedgejoin.so\"}");
    }
    stop_client_fn stop_client = (stop_client_fn) dlsym(handle, "EdgeStopClient");
    free_result_fn free_result = (free_result_fn) dlsym(handle, "EdgeJoinFree");
    if (stop_client == NULL || free_result == NULL) {
        dlclose(handle);
        return error_result(env, "{\"ok\":false,\"status_code\":0,\"error\":\"missing stop symbols\"}");
    }
    jstring output = copy_result(env, stop_client(), free_result);
    dlclose(handle);
    return output;
}

JNIEXPORT jstring JNICALL
Java_ai_edgez_androiddevtools_NativeBridge_nativePairWireless(
        JNIEnv *env, jclass clazz, jstring pair_host, jint pair_port,
        jstring code, jstring debug_host, jint debug_port) {
    (void) clazz;
    void *handle = open_library();
    if (handle == NULL) {
        return error_result(env, "{\"ok\":false,\"status_code\":0,\"error\":\"failed to load libedgejoin.so\"}");
    }
    pair_wireless_fn pair_wireless = (pair_wireless_fn) dlsym(handle, "EdgePairWireless");
    free_result_fn free_result = (free_result_fn) dlsym(handle, "EdgeJoinFree");
    if (pair_wireless == NULL || free_result == NULL) {
        dlclose(handle);
        return error_result(env, "{\"ok\":false,\"status_code\":0,\"error\":\"missing pairing symbols\"}");
    }
    const char *pair = (*env)->GetStringUTFChars(env, pair_host, NULL);
    const char *pair_code = (*env)->GetStringUTFChars(env, code, NULL);
    const char *debug = (*env)->GetStringUTFChars(env, debug_host, NULL);
    char *result = pair_wireless(pair, pair_port, pair_code, "", debug, debug_port);
    (*env)->ReleaseStringUTFChars(env, pair_host, pair);
    (*env)->ReleaseStringUTFChars(env, code, pair_code);
    (*env)->ReleaseStringUTFChars(env, debug_host, debug);
    jstring output = copy_result(env, result, free_result);
    dlclose(handle);
    return output;
}

JNIEXPORT jstring JNICALL
Java_ai_edgez_androiddevtools_NativeBridge_nativeSetAdbProxyTarget(
        JNIEnv *env, jclass clazz, jstring host, jint port) {
    (void) clazz;
    void *handle = open_library();
    if (handle == NULL) {
        return error_result(env, "{\"ok\":false,\"status_code\":0,\"error\":\"failed to load libedgejoin.so\"}");
    }
    set_adb_target_fn set_target = (set_adb_target_fn) dlsym(handle, "EdgeSetAdbProxyTarget");
    free_result_fn free_result = (free_result_fn) dlsym(handle, "EdgeJoinFree");
    if (set_target == NULL || free_result == NULL) {
        dlclose(handle);
        return error_result(env, "{\"ok\":false,\"status_code\":0,\"error\":\"missing target symbols\"}");
    }
    const char *value = (*env)->GetStringUTFChars(env, host, NULL);
    char *result = set_target(value, port);
    (*env)->ReleaseStringUTFChars(env, host, value);
    jstring output = copy_result(env, result, free_result);
    dlclose(handle);
    return output;
}
