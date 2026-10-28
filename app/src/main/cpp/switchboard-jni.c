/*
 ============================================================================
 Name        : switchboard-jni.c
 Description : JNI bridge from the Switchboard Android app to libportmux.
 *
 * Exposes a small surface to Kotlin:
 *   nativeNew()                         -> long handle
 *   nativeAddRoute(h, proto, la, lp, ta, tp, egressKind, egressArg)
 *   nativeAddSocks(h, la, lp, egressKind, egressArg, networkHandle)
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
#include <pthread.h>
#include <stdatomic.h>
#include <android/log.h>
#include <android/multinetwork.h>

#include "portmux.h"
#include "portmux-tunnel.h"


#define TAG "switchboard-jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

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
        __android_log_print (ANDROID_LOG_WARN, TAG,
            "provide_pinned_fd: xnix-netd returned %d for if=%s", fd,
            pc ? pc->ifname : "(null)");
    return fd;
}

/* Per-route callback context for CALLBACK egress.
 *
 * The network handle is LIVE, not frozen at add time: a long-lived SOCKS proxy
 * opens many upstream connections over a session, and the pinned Android
 * Network can be lost and regained (with a *different* net_handle_t) mid-flight
 * — e.g. a Wi-Fi<->cellular handoff, doze, or a GrapheneOS Network-permission
 * toggle. bind_hook therefore reads the handle atomically on every connect, and
 * NetworkRequester pushes updates via nativeUpdateNetworkHandle() on
 * onAvailable/onLost. A handle of 0 means "network currently unavailable" and
 * bind_hook fails closed (aborts the upstream connect) rather than leaking the
 * connection onto the default network. @token groups contexts that share one
 * transport (NetworkRequester keys its requests by transport). */
typedef struct {
    _Atomic net_handle_t network_handle; /* live; 0 = unavailable (fail closed) */
    int token;                           /* transport group, for live updates */
} EgressCtx;

/* Registry of live CALLBACK contexts so an out-of-band handle update (from the
 * ConnectivityManager callback thread) can reach every EgressCtx sharing a
 * token. Built on the worker thread at add time; read/written under a mutex.
 * Contexts live for the whole forwarder run (intentionally leaked at process
 * exit), so the registry never removes individual entries — it is reset
 * wholesale when a new engine instance is created. */
#define MAX_EGRESS_CTX 128
static pthread_mutex_t g_ctx_lock = PTHREAD_MUTEX_INITIALIZER;
static EgressCtx *g_ctxs[MAX_EGRESS_CTX];
static int g_ctx_count;

static void
ctx_registry_reset (void)
{
    pthread_mutex_lock (&g_ctx_lock);
    g_ctx_count = 0;
    memset (g_ctxs, 0, sizeof g_ctxs);
    pthread_mutex_unlock (&g_ctx_lock);
}

static void
ctx_registry_add (EgressCtx *ctx)
{
    pthread_mutex_lock (&g_ctx_lock);
    if (g_ctx_count < MAX_EGRESS_CTX)
        g_ctxs[g_ctx_count++] = ctx;
    else
        LOGW ("egress ctx registry full (%d); live updates for this route "
              "will not propagate", MAX_EGRESS_CTX);
    pthread_mutex_unlock (&g_ctx_lock);
}

/* Update every live CALLBACK context whose token matches. netHandle==0 marks
   the transport unavailable (fail-closed on subsequent connects). */
static void
ctx_registry_update (int token, net_handle_t netHandle)
{
    pthread_mutex_lock (&g_ctx_lock);
    for (int i = 0; i < g_ctx_count; i++) {
        if (g_ctxs[i] && g_ctxs[i]->token == token)
            atomic_store (&g_ctxs[i]->network_handle, netHandle);
    }
    pthread_mutex_unlock (&g_ctx_lock);
}

/* Called by portmux on a freshly created upstream socket (before connect).
   Binds the fd to a specific Android Network entirely in native code. Reads
   the LIVE handle so a mid-session network change is honored per connection. */
static int
bind_hook (int fd, void *user_data)
{
    EgressCtx *ctx = (EgressCtx *)user_data;
    if (!ctx)
        return 0;

    net_handle_t h = atomic_load (&ctx->network_handle);
    if (h == 0) {
        /* No pinning requested, or the pinned network is currently gone.
           If a token was assigned (token>0), this route explicitly pinned a
           transport, so a 0 handle means "unavailable" -> fail closed. A
           token of 0 means no pinning was ever requested -> allow default. */
        if (ctx->token > 0) {
            LOGW ("bind_hook: network for token=%d unavailable; failing closed "
                  "(fd=%d)", ctx->token, fd);
            return -1;
        }
        return 0;
    }

    int rc = android_setsocknetwork (h, fd);
    if (rc != 0) {
        LOGW ("bind_hook: android_setsocknetwork(handle=%llu, fd=%d) failed: %s",
              (unsigned long long)h, fd, strerror (errno));
        return -1;
    }
    LOGI ("bind_hook: fd=%d pinned to network handle=%llu (token=%d)", fd,
          (unsigned long long)h, ctx->token);
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
    /* A new engine instance owns a fresh set of egress contexts; drop any from
       a previous run so stale pointers are never updated. */
    ctx_registry_reset ();
    return (jlong)(intptr_t)portmux_new ();
}

/* Build a PortmuxEgress from the egressKind/egressArg/networkHandle surface
   shared by nativeAddRoute and nativeAddSocks. On return *egp either points at
   @eg (and @eg is populated) or is NULL for PORTMUX_EGRESS_NONE.
   egressKind: 0 NONE, 1 SOURCE (arg=ip), 2 DEVICE (arg=ifname),
               3 CALLBACK (arg unused; networkHandle carries the initial
               Network, @token groups it for live updates).
   portmux copies primitive fields but keeps our string/ctx pointers by
   reference, so the small strdup/ctx allocations are intentionally leaked for
   the life of the forwarder (freed implicitly at process exit). */
static void
build_egress (jint egressKind, const char *arg, jlong networkHandle,
              jint token, PortmuxEgress *eg, PortmuxEgress **egp)
{
    memset (eg, 0, sizeof *eg);

    switch (egressKind) {
    case 1:
        eg->kind = PORTMUX_EGRESS_SOURCE;
        eg->source_ip = strdup (arg);
        *egp = eg;
        break;
    case 2: {
        /* Interface pinning: an app cannot hold CAP_NET_RAW (AOSP
           app.te:600 neverallow), so delegate to xnix-netd via
           PORTMUX_EGRESS_PROVIDED_FD — the daemon mints an
           SO_BINDTODEVICE-pinned fd and portmux adopts it. */
        ProvCtx *pc = calloc (1, sizeof (ProvCtx));
        strncpy (pc->ifname, arg, sizeof (pc->ifname) - 1);
        eg->kind = PORTMUX_EGRESS_PROVIDED_FD;
        eg->provider = provide_pinned_fd;
        eg->callback_data = pc;
        *egp = eg;
        break;
    }
    case 3: {
        EgressCtx *ctx = calloc (1, sizeof (EgressCtx));
        atomic_init (&ctx->network_handle, (net_handle_t)networkHandle);
        ctx->token = token;
        ctx_registry_add (ctx);
        eg->kind = PORTMUX_EGRESS_CALLBACK;
        eg->callback = bind_hook;
        eg->callback_data = ctx;
        *egp = eg;
        break;
    }
    default:
        *egp = NULL; /* NONE */
    }
}

/* Push a live network-handle update for every CALLBACK route sharing @token.
   Called from the ConnectivityManager callback thread (via NetworkRequester)
   on a Wi-Fi<->cellular handoff etc. netHandle==0 marks the transport gone;
   bind_hook then fails closed until a new handle arrives. */
JNIEXPORT void JNICALL
Java_org_xnet_switchboard_SwitchboardNative_nativeUpdateNetworkHandle (
    JNIEnv *env, jclass c, jint token, jlong netHandle)
{
    (void)env; (void)c;
    ctx_registry_update ((int)token, (net_handle_t)netHandle);
    LOGI ("nativeUpdateNetworkHandle: token=%d handle=%llu", (int)token,
          (unsigned long long)netHandle);
}

/* egressKind: 0 NONE, 1 SOURCE (egressArg=ip), 2 DEVICE (egressArg=ifname),
               3 CALLBACK (egressArg unused; networkHandle carries the Network) */
JNIEXPORT jint JNICALL
Java_org_xnet_switchboard_SwitchboardNative_nativeAddRoute (
    JNIEnv *env, jclass c, jlong h, jint proto,
    jstring listenAddr, jint listenPort,
    jstring targetAddr, jint targetPort,
    jint egressKind, jstring egressArg, jlong networkHandle, jint token)
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
    build_egress (egressKind, arg, networkHandle, token, &eg, &egp);

    PortmuxProto p = (proto == 1) ? PORTMUX_PROTO_UDP : PORTMUX_PROTO_TCP;
    return portmux_add_route (pm, p, la, listenPort, ta, targetPort, egp);
}

/* Register a SOCKS5 proxy listener. Like nativeAddRoute there is no fixed
   target — each SOCKS client supplies the destination per connection — but the
   same egress policy is applied to every upstream socket the proxy opens, so
   the no-root "SOCKS over cellular/wifi" pin works via CALLBACK egress exactly
   like a normal route. egressKind/egressArg/networkHandle mirror nativeAddRoute. */
JNIEXPORT jint JNICALL
Java_org_xnet_switchboard_SwitchboardNative_nativeAddSocks (
    JNIEnv *env, jclass c, jlong h,
    jstring listenAddr, jint listenPort,
    jint egressKind, jstring egressArg, jlong networkHandle, jint token)
{
    (void)c;
    Portmux *pm = (Portmux *)(intptr_t)h;
    if (!pm) return -1;

    char la[128], arg[128];
    jstr (env, listenAddr, la, sizeof la);
    jstr (env, egressArg, arg, sizeof arg);

    PortmuxEgress eg;
    PortmuxEgress *egp = NULL;
    build_egress (egressKind, arg, networkHandle, token, &eg, &egp);

    return portmux_add_socks (pm, la, listenPort, egp);
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

/* Parse 64 hex chars -> 32 PSK bytes. Returns 0 on success. */
static int
parse_psk_hex (const char *hex, unsigned char out[PM_TUNNEL_PSK_LEN])
{
    if (!hex) return -1;
    size_t n = strlen (hex);
    if (n != PM_TUNNEL_PSK_LEN * 2) return -1;
    for (int i = 0; i < PM_TUNNEL_PSK_LEN; i++) {
        unsigned v;
        if (sscanf (hex + i * 2, "%2x", &v) != 1) return -1;
        out[i] = (unsigned char)v;
    }
    return 0;
}

/* Encrypted tunnel CLIENT (NoisePSK_NN): dial serverAddr:serverPort and splice
   to targetAddr:targetPort. pskHex = 64 hex chars. cipher 0=AES-GCM, 1=ChaCha.
   The outbound leg honors the egress (incl. the no-root NETWORK pin + live
   handle), reusing build_egress exactly like a normal route.
   REVERSE (v0.4.0): rlistenAddr/rlistenPort open a REVERSE LISTENER on the
   client (connections there become client-originated reverse streams the server
   splices to rtargetAddr:rtargetPort). rproto: 0=TCP, 1=UDP, 2=BOTH (dual-stack).
   When rlistenPort=0, no reverse (backward compatible with v0.3.0). */
JNIEXPORT jint JNICALL
Java_org_xnet_switchboard_SwitchboardNative_nativeAddTunnelClient (
    JNIEnv *env, jclass c, jlong h,
    jstring serverAddr, jint serverPort,
    jstring targetAddr, jint targetPort,
    jstring pskHex, jint cipher,
    jint egressKind, jstring egressArg, jlong networkHandle, jint token,
    jstring rlistenAddr, jint rlistenPort,
    jstring rtargetAddr, jint rtargetPort, jint rproto)
{
    (void)c;
    Portmux *pm = (Portmux *)(intptr_t)h;
    if (!pm) return -1;

    char sa[128], ta[128], arg[128], pk[128];
    char rla[128], rta[128];
    jstr (env, serverAddr, sa, sizeof sa);
    jstr (env, targetAddr, ta, sizeof ta);
    jstr (env, egressArg, arg, sizeof arg);
    jstr (env, pskHex, pk, sizeof pk);
    jstr (env, rlistenAddr, rla, sizeof rla);
    jstr (env, rtargetAddr, rta, sizeof rta);

    unsigned char psk[PM_TUNNEL_PSK_LEN];
    if (parse_psk_hex (pk, psk) < 0) {
        LOGW ("add_tunnel: bad PSK (need 64 hex chars)");
        return -1;
    }

    PortmuxEgress eg;
    PortmuxEgress *egp = NULL;
    build_egress (egressKind, arg, networkHandle, token, &eg, &egp);

    PmTunnelCipher cph = (cipher == 1) ? PM_TUNNEL_CIPHER_CHACHAPOLY
                                       : PM_TUNNEL_CIPHER_AESGCM;
    /* v0.4.0: pass rlistenAddr/rlistenPort as listen_addr/listen_port when
       reverse is requested (rlistenPort>0), else NULL,0 for forward-only. */
    const char *la = (rlistenPort > 0) ? (rla[0] ? rla : NULL) : NULL;
    int lp = (rlistenPort > 0) ? (int)rlistenPort : 0;
    int rc = portmux_add_tunnel_client (pm, la, lp,
                                        sa, (int)serverPort,
                                        ta[0] ? ta : "127.0.0.1", (int)targetPort,
                                        psk, cph, egp);
    if (rc == 0 && rtargetPort > 0) {
        /* Set the SERVER-side reverse target (where the server connects on
           a reverse OPEN initiated by the client). */
        if (portmux_tunnel_set_reverse_target (pm, rta[0] ? rta : "127.0.0.1",
                                               (int)rtargetPort) < 0) {
            LOGW ("add_tunnel: set_reverse_target failed");
        }
    }
    if (rc == 0 && rlistenPort > 0) {
        /* Set the reverse listener protocol: 0=TCP, 1=UDP, 2=BOTH. */
        if (portmux_tunnel_set_reverse_proto (pm, (int)rproto) < 0) {
            LOGW ("add_tunnel: set_reverse_proto failed");
        }
    }
    memset (psk, 0, sizeof psk);
    LOGI ("add_tunnel rc=%d server=%s:%d target=%s:%d cipher=%d token=%d "
          "rev=%s:%d->%s:%d proto=%d",
          rc, sa, (int)serverPort, ta, (int)targetPort, (int)cipher, (int)token,
          la ? la : "(none)", lp, rta[0] ? rta : "(none)", (int)rtargetPort,
          (int)rproto);
    return rc;
}
