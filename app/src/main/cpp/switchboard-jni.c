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
 * Egress CALLBACK (network pinning) is handled entirely in native code via
 * android_setsocknetwork() from libandroid (NDK, API 23+). The networkHandle
 * passed from Kotlin is Network.getNetworkHandle(), which is exactly the
 * net_handle_t that android_setsocknetwork expects. This avoids a JNI up-call
 * from the portmux worker thread (which previously crashed with an invalid
 * JNI transition frame).
 ============================================================================
 */

#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <errno.h>
#include <android/log.h>
#include <android/multinetwork.h>

#include "portmux.h"

/* from xnix-netd-client.c: fetch an interface-pinned/low-port socket
   minted by the privileged xnix-netd daemon (via SCM_RIGHTS). */
extern int xnd_request_socket (int af, int type, const char *ifname, int port);

/* Provider ctx for PORTMUX_EGRESS_PROVIDED_FD (DEVICE-mode interface pin). */
typedef struct {
    char ifname[32];
} ProvCtx;

/* portmux PortmuxFdProvider: hand back a daemon-minted, iface-pinned fd. */
static int
provide_pinned_fd (int family, int socktype, void *user_data)
{
    ProvCtx *pc = (ProvCtx *)user_data;
    int af = family;
    int ty = socktype; /* SOCK_STREAM/DGRAM already */
    int fd = xnd_request_socket (af, ty, pc ? pc->ifname : (void *)0, 0);
    if (fd < 0)
        LOGW ("provide_pinned_fd: xnix-netd returned %d for if=%s", fd,
              pc ? pc->ifname : "(null)");
    return fd;
}

#define TAG "switchboard-jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

/* Per-route callback context for CALLBACK egress. */
typedef struct {
    net_handle_t network_handle; /* Network.getNetworkHandle() (0 = none) */
} EgressCtx;

/* Called by portmux on a freshly created upstream socket (before connect).
   Binds the fd to a specific Android Network entirely in native code. */
static int
bind_hook (int fd, void *user_data)
{
    EgressCtx *ctx = (EgressCtx *)user_data;

    if (!ctx || ctx->network_handle == 0)
        return 0; /* no pinning requested */

    int rc = android_setsocknetwork (ctx->network_handle, fd);
    if (rc != 0) {
        LOGW ("bind_hook: android_setsocknetwork(handle=%llu, fd=%d) failed: %s",
              (unsigned long long)ctx->network_handle, fd, strerror (errno));
        return -1;
    }
    LOGI ("bind_hook: fd=%d pinned to network handle=%llu", fd,
          (unsigned long long)ctx->network_handle);
    return 0;
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
    case 2: {
        /* Interface pinning: an app cannot hold CAP_NET_RAW (AOSP
           app.te:600 neverallow), so delegate to xnix-netd via
           PORTMUX_EGRESS_PROVIDED_FD — the daemon mints an
           SO_BINDTODEVICE-pinned fd and portmux adopts it. */
        ProvCtx *pc = calloc (1, sizeof (ProvCtx));
        strncpy (pc->ifname, arg, sizeof (pc->ifname) - 1);
        eg.kind = PORTMUX_EGRESS_PROVIDED_FD;
        eg.provider = provide_pinned_fd;
        eg.callback_data = pc;
        egp = &eg;
        break;
    }
    case 3: {
        EgressCtx *ctx = calloc (1, sizeof (EgressCtx));
        ctx->network_handle = (net_handle_t)networkHandle;
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
