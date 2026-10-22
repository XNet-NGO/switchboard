/*
 ============================================================================
 Name        : switchboard-jni.c
 Description : JNI bridge from the Switchboard Android app to libportmux.
 *
 * Exposes a small surface to Kotlin:
 *   nativeNew()                         -> long handle
 *   nativeAddRoute(h, proto, la, lp, ta, tp, egressKind, egressArg)
 *   nativeRun(h)                        (BLOCKS - call on a worker thread)
 *   nativeStop(h)
 *   nativeDestroy(h)
 *
 * Egress CALLBACK is wired to Kotlin: when a route uses EGRESS kind 3
 * (callback), portmux invokes bind_hook() which up-calls
 * SwitchboardNative.bindSocketToNetwork(fd, networkHandle) so Kotlin can call
 * Network.bindSocket(fd) - the no-root way to pin egress to a chosen network.
 ============================================================================
 */

#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <android/log.h>

#include "portmux.h"

#define TAG "switchboard-jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

static JavaVM *g_vm;

/* Per-route callback context for CALLBACK egress. */
typedef struct {
    jlong network_handle; /* opaque Android Network handle (0 = none) */
} EgressCtx;

JNIEXPORT jint JNICALL
JNI_OnLoad (JavaVM *vm, void *reserved)
{
    (void)reserved;
    g_vm = vm;
    return JNI_VERSION_1_6;
}

/* Called by portmux on a freshly created upstream socket (before connect).
   Up-calls into Kotlin to bind the fd to a specific Android Network. */
static int
bind_hook (int fd, void *user_data)
{
    EgressCtx *ctx = (EgressCtx *)user_data;
    JNIEnv *env = NULL;
    jclass cls;
    jmethodID mid;
    int attached = 0;
    int rc = 0;

    if (!ctx || ctx->network_handle == 0)
        return 0; /* no pinning requested */

    if ((*g_vm)->GetEnv (g_vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*g_vm)->AttachCurrentThread (g_vm, &env, NULL) != JNI_OK) {
            LOGW ("bind_hook: AttachCurrentThread failed");
            return -1;
        }
        attached = 1;
    }

    cls = (*env)->FindClass (env, "org/xnet/switchboard/SwitchboardNative");
    if (!cls) {
        LOGW ("bind_hook: SwitchboardNative not found");
        rc = -1;
        goto done;
    }
    mid = (*env)->GetStaticMethodID (env, cls, "bindSocketToNetwork", "(IJ)Z");
    if (!mid) {
        LOGW ("bind_hook: bindSocketToNetwork not found");
        rc = -1;
        goto done;
    }
    {
        jboolean ok = (*env)->CallStaticBooleanMethod (env, cls, mid,
                                                       (jint)fd,
                                                       ctx->network_handle);
        rc = ok ? 0 : -1;
    }

done:
    if (attached)
        (*g_vm)->DetachCurrentThread (g_vm);
    return rc;
}

static const char *
jstr (JNIEnv *env, jstring s, char *buf, size_t n)
{
    if (!s) { buf[0] = '\0'; return buf; }
    const char *c = (*env)->GetStringUTFChars (env, s, NULL);
    if (!c) { buf[0] = '\0'; return buf; }
    strncpy (buf, c, n - 1);
    buf[n - 1] = '\0';
    (*env)->ReleaseStringUTFChars (env, s, c);
    return buf;
}

JNIEXPORT jlong JNICALL
Java_org_xnet_switchboard_SwitchboardNative_nativeNew (JNIEnv *env, jclass c)
{
    (void)env; (void)c;
    return (jlong)(intptr_t)portmux_new ();
}

/* egressKind: 0 NONE, 1 SOURCE (egressArg=ip), 2 DEVICE (egressArg=ifname),
               3 CALLBACK (egressArg unused; networkHandle carries the Network) */
JNIEXPORT jint JNICALL
Java_org_xnet_switchboard_SwitchboardNative_nativeAddRoute (
    JNIEnv *env, jclass c, jlong h, jint proto,
    jstring listenAddr, jint listenPort,
    jstring targetAddr, jint targetPort,
    jint egressKind, jstring egressArg, jlong networkHandle)
{
    (void)c;
    Portmux *pm = (Portmux *)(intptr_t)h;
    if (!pm) return -1;

    char la[128], ta[128], arg[128];
    jstr (env, listenAddr, la, sizeof la);
    jstr (env, targetAddr, ta, sizeof ta);
    jstr (env, egressArg, arg, sizeof arg);

    PortmuxEgress eg;
    PortmuxEgress *egp = NULL;
    memset (&eg, 0, sizeof eg);

    switch (egressKind) {
    case 1:
        eg.kind = PORTMUX_EGRESS_SOURCE;
        eg.source_ip = strdup (arg);
        egp = &eg;
        break;
    case 2:
        eg.kind = PORTMUX_EGRESS_DEVICE;
        eg.device = strdup (arg);
        egp = &eg;
        break;
    case 3: {
        EgressCtx *ctx = calloc (1, sizeof (EgressCtx));
        ctx->network_handle = networkHandle;
        eg.kind = PORTMUX_EGRESS_CALLBACK;
        eg.callback = bind_hook;
        eg.callback_data = ctx;
        egp = &eg;
        break;
    }
    default:
        egp = NULL; /* NONE */
    }

    PortmuxProto p = (proto == 1) ? PORTMUX_PROTO_UDP : PORTMUX_PROTO_TCP;
    int rc = portmux_add_route (pm, p, la, listenPort, ta, targetPort, egp);
    /* portmux copies primitive fields but keeps our string/ctx pointers by
       reference, so we intentionally leak the small strdup/ctx for the life of
       the forwarder (freed implicitly at process exit). Routes are few. */
    return rc;
}

JNIEXPORT void JNICALL
Java_org_xnet_switchboard_SwitchboardNative_nativeRun (JNIEnv *env, jclass c,
                                                       jlong h)
{
    (void)env; (void)c;
    Portmux *pm = (Portmux *)(intptr_t)h;
    if (pm) portmux_run (pm); /* blocks until stop */
}

JNIEXPORT void JNICALL
Java_org_xnet_switchboard_SwitchboardNative_nativeStop (JNIEnv *env, jclass c,
                                                        jlong h)
{
    (void)env; (void)c;
    Portmux *pm = (Portmux *)(intptr_t)h;
    if (pm) portmux_stop (pm);
}

JNIEXPORT void JNICALL
Java_org_xnet_switchboard_SwitchboardNative_nativeDestroy (JNIEnv *env,
                                                           jclass c, jlong h)
{
    (void)env; (void)c;
    Portmux *pm = (Portmux *)(intptr_t)h;
    if (pm) portmux_destroy (pm);
}
