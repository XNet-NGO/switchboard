/*
 * xnix-netd-client.c — Switchboard-side client for the xnix-netd privileged
 * socket helper. Connects to the daemon's abstract AF_UNIX socket, requests an
 * interface-pinned / low-port-bound socket, and receives the resulting fd via
 * SCM_RIGHTS.
 *
 * WHY: Switchboard is an unprivileged app and (by AOSP app.te:600 neverallow)
 * can never hold CAP_NET_RAW, so SO_BINDTODEVICE cannot be done in-process.
 * xnix-netd (privileged daemon) mints the pinned fd; this client fetches it.
 *
 * INTEGRATION STATUS (branch feat/xnix-netd-fd-helper — NEEDS portmux change):
 *   portmux currently CREATES the upstream socket itself and only exposes a
 *   post-create bind_hook(fd). To use a daemon-minted, interface-pinned fd,
 *   portmux must accept an EXTERNALLY SUPPLIED fd for the upstream socket
 *   (a new portmux API, e.g. PORTMUX_EGRESS_PROVIDED_FD). Until that engine
 *   API exists, this client is complete but not wired into the data path.
 *   See README / xnetos-mods for the daemon side.
 */
#include <errno.h>
#include <net/if.h>
#include <stddef.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <android/log.h>

#define TAG "xnix-netd-client"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

#define SOCK_NAME  "xnix-netd"
#define IFNAMSZ    16

struct xnd_req {
    unsigned char version;   /* 1 */
    unsigned char domain;    /* 0 v4, 1 v6 */
    unsigned char type;      /* 0 stream, 1 dgram */
    unsigned char flags;     /* 1 pin iface, 2 bind port */
    unsigned short port;
    unsigned short _pad;
    char ifname[IFNAMSZ];
};
struct xnd_resp { int err; };

/*
 * Request a socket from xnix-netd.
 *   af:   AF_INET / AF_INET6
 *   type: SOCK_STREAM / SOCK_DGRAM
 *   ifname: non-NULL -> SO_BINDTODEVICE pin; NULL -> no pin
 *   port:  >0 -> bind that (possibly low) port; 0 -> no bind
 * Returns a usable fd (>=0) or -errno.
 */
int xnd_request_socket(int af, int type, const char *ifname, int port) {
    int c = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (c < 0) return -errno;

    struct sockaddr_un sa = { .sun_family = AF_UNIX };
    sa.sun_path[0] = '\0';
    memcpy(sa.sun_path + 1, SOCK_NAME, strlen(SOCK_NAME));
    socklen_t slen = offsetof(struct sockaddr_un, sun_path) + 1 + strlen(SOCK_NAME);
    if (connect(c, (struct sockaddr *)&sa, slen) < 0) { int e=-errno; close(c); return e; }

    struct xnd_req q;
    memset(&q, 0, sizeof q);
    q.version = 1;
    q.domain  = (af == AF_INET6) ? 1 : 0;
    q.type    = (type == SOCK_DGRAM) ? 1 : 0;
    if (ifname && *ifname) { q.flags |= 1; strncpy(q.ifname, ifname, IFNAMSZ-1); }
    if (port > 0)          { q.flags |= 2; q.port = (unsigned short)port; }

    if (send(c, &q, sizeof q, 0) != (ssize_t)sizeof q) { int e=-errno; close(c); return e; }

    /* receive resp + fd */
    struct xnd_resp r = { .err = -EIO };
    struct iovec iov = { .iov_base = &r, .iov_len = sizeof r };
    char cbuf[CMSG_SPACE(sizeof(int))] = {0};
    struct msghdr msg = { .msg_iov = &iov, .msg_iovlen = 1,
                          .msg_control = cbuf, .msg_controllen = sizeof cbuf };
    ssize_t n = recvmsg(c, &msg, 0);
    close(c);
    if (n < (ssize_t)sizeof r) return -EIO;
    if (r.err != 0) return r.err;

    for (struct cmsghdr *cm = CMSG_FIRSTHDR(&msg); cm; cm = CMSG_NXTHDR(&msg, cm)) {
        if (cm->cmsg_level == SOL_SOCKET && cm->cmsg_type == SCM_RIGHTS) {
            int fd; memcpy(&fd, CMSG_DATA(cm), sizeof fd);
            return fd;
        }
    }
    return -EIO;
}
