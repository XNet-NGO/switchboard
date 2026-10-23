/*
 ============================================================================
 Name        : test_egress_live_handle.c
 Description : Host unit test for the live network-handle refresh in
               switchboard-jni.c (Option A fix for the mid-session
               network-revocation latching flaw).
 *
 * The production registry + bind_hook are static in switchboard-jni.c and
 * depend on JNI/NDK headers, so this test re-implements the SAME logic with a
 * mocked android_setsocknetwork() and asserts the behavior the fix guarantees:
 *
 *   1. A pinned route (token>0) binds every connection to the CURRENT handle.
 *   2. On loss (handle -> 0) subsequent connects FAIL CLOSED (return -1),
 *      never silently falling back to the default network.
 *   3. On regain with a DIFFERENT handle, connects succeed again WITHOUT
 *      recreating the context (the latch is gone).
 *   4. token==0 (no pin) always allows the default network (returns 0).
 *   5. ctx_registry_update only touches contexts sharing the token.
 *
 * Build/run (host):
 *   cc -std=gnu11 -Wall -Wextra -pthread test_egress_live_handle.c -o /tmp/t && /tmp/t
 ============================================================================
 */

#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <stdatomic.h>
#include <pthread.h>

typedef unsigned long long net_handle_t;

/* ---- mock of the NDK call bind_hook uses ---- */
static net_handle_t g_last_bound_handle;
static int g_bind_calls;
static int g_setsocknetwork_fail; /* force failure to exercise error path */

static int
android_setsocknetwork (net_handle_t h, int fd)
{
    (void)fd;
    g_bind_calls++;
    if (g_setsocknetwork_fail)
        return -1;
    g_last_bound_handle = h;
    return 0;
}

/* ---- logic copied verbatim from switchboard-jni.c ---- */
typedef struct {
    _Atomic net_handle_t network_handle;
    int token;
} EgressCtx;

#define MAX_EGRESS_CTX 128
static pthread_mutex_t g_ctx_lock = PTHREAD_MUTEX_INITIALIZER;
static EgressCtx *g_ctxs[MAX_EGRESS_CTX];
static int g_ctx_count;

static void ctx_registry_reset (void) {
    pthread_mutex_lock (&g_ctx_lock);
    g_ctx_count = 0;
    memset (g_ctxs, 0, sizeof g_ctxs);
    pthread_mutex_unlock (&g_ctx_lock);
}
static void ctx_registry_add (EgressCtx *ctx) {
    pthread_mutex_lock (&g_ctx_lock);
    if (g_ctx_count < MAX_EGRESS_CTX) g_ctxs[g_ctx_count++] = ctx;
    pthread_mutex_unlock (&g_ctx_lock);
}
static void ctx_registry_update (int token, net_handle_t netHandle) {
    pthread_mutex_lock (&g_ctx_lock);
    for (int i = 0; i < g_ctx_count; i++)
        if (g_ctxs[i] && g_ctxs[i]->token == token)
            atomic_store (&g_ctxs[i]->network_handle, netHandle);
    pthread_mutex_unlock (&g_ctx_lock);
}
static int bind_hook (int fd, void *user_data) {
    EgressCtx *ctx = (EgressCtx *)user_data;
    if (!ctx) return 0;
    net_handle_t h = atomic_load (&ctx->network_handle);
    if (h == 0) {
        if (ctx->token > 0) return -1; /* pinned but gone -> fail closed */
        return 0;                      /* no pin -> default ok */
    }
    int rc = android_setsocknetwork (h, fd);
    return rc != 0 ? -1 : 0;
}

/* ---- helpers ---- */
static EgressCtx *make_ctx (net_handle_t h, int token) {
    EgressCtx *c = calloc (1, sizeof *c);
    atomic_init (&c->network_handle, h);
    c->token = token;
    ctx_registry_add (c);
    return c;
}

static int failures;
#define CHECK(cond, msg) do { \
    if (!(cond)) { printf("FAIL: %s\n", msg); failures++; } \
    else          { printf("ok  : %s\n", msg); } \
} while (0)

int main (void) {
    ctx_registry_reset ();

    /* A SOCKS route pinned to a transport, token=2 (e.g. CELLULAR ordinal+1),
       initial handle 1111. */
    EgressCtx *socks = make_ctx (1111ULL, 2);
    /* An unrelated pinned route on another token. */
    EgressCtx *other = make_ctx (2222ULL, 3);
    /* A non-pinned route (token 0). */
    EgressCtx *nopin = make_ctx (0ULL, 0);

    /* 1. Normal: binds to the current handle. */
    g_bind_calls = 0;
    CHECK (bind_hook (10, socks) == 0, "pinned connect succeeds");
    CHECK (g_last_bound_handle == 1111ULL, "bound to initial handle 1111");

    /* 2. Network lost mid-session: handle -> 0 for token 2 only. */
    ctx_registry_update (2, 0ULL);
    g_bind_calls = 0;
    CHECK (bind_hook (11, socks) == -1, "after loss, pinned connect FAILS CLOSED");
    CHECK (g_bind_calls == 0, "no setsocknetwork attempted when handle gone");
    /* the other token must be untouched */
    CHECK (atomic_load (&other->network_handle) == 2222ULL,
           "update(token=2) did not touch token=3");

    /* 3. Network regained with a DIFFERENT handle (handles are not stable). */
    ctx_registry_update (2, 3333ULL);
    CHECK (bind_hook (12, socks) == 0, "after regain, pinned connect succeeds again");
    CHECK (g_last_bound_handle == 3333ULL, "now bound to NEW handle 3333 (no latch)");

    /* 4. Non-pinned route always allows the default network. */
    CHECK (bind_hook (13, nopin) == 0, "token=0 (no pin) allows default");
    ctx_registry_update (0, 0ULL);
    CHECK (bind_hook (14, nopin) == 0, "token=0 stays allowed after a 0 update");

    /* 5. setsocknetwork failure path still aborts the connect (no leak). */
    g_setsocknetwork_fail = 1;
    CHECK (bind_hook (15, socks) == -1, "setsocknetwork failure aborts connect");
    g_setsocknetwork_fail = 0;

    /* 6. reset clears the registry (new engine instance). */
    ctx_registry_reset ();
    /* After reset, updating the old token is a no-op (count==0); the stale ctx
       pointer is never dereferenced by the registry again. */
    ctx_registry_update (2, 9999ULL);
    CHECK (g_ctx_count == 0, "registry reset empties the context list");

    if (failures) { printf ("\nRESULT: %d failure(s)\n", failures); return 1; }
    printf ("\nRESULT: all passed\n");
    return 0;
}
