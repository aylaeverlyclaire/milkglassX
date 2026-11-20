/**
 * Hardened Anti-SSRF URL validation for public HTTPS targets.
 * Blocks non-HTTPS protocols, embedded credentials, localhost/local/internal domains,
 * IPv6 literals (loopback/ULA/link-local/mapped), numeric/hex/octal IP encodings,
 * and private/loopback/link-local/CGNAT (100.64.0.0/10)/reserved IPv4 ranges.
 */
export function isSafePublicHttpsUrl(rawUrl: string): URL | null {
  try {
    const u = new URL(rawUrl);
    if (u.protocol !== 'https:') return null;
    if (u.username || u.password) return null;
    if (u.port && u.port !== '443') return null;
    if (u.hostname.endsWith('.')) return null;

    const host = u.hostname.toLowerCase();
    if (!host) return null;

    // Block localhost, local/internal hostnames, and cloud metadata endpoints.
    if (
      host === 'localhost' ||
      host.endsWith('.localhost') ||
      host.endsWith('.local') ||
      host.endsWith('.internal') ||
      host.endsWith('.home.arpa') ||
      host === 'metadata.google.internal'
    ) {
      return null;
    }

    // Block all IPv6 literals (including ::1, fc00::/7, fe80::/10, ::ffff:127.0.0.1).
    if (host.includes(':') || host.startsWith('[') || host.endsWith(']')) {
      return null;
    }

    // Block single-integer or hex-encoded IPv4 hostnames.
    if (/^\d+$/.test(host) || /^0x[0-9a-f]+$/i.test(host)) {
      return null;
    }

    // Check dotted-quad IPv4 literals (including octal/hex octet attempts).
    if (/^[0-9a-fx]+(\.[0-9a-fx]+){1,3}$/i.test(host)) {
      const parts = host.split('.');
      if (parts.length !== 4) return null;
      const octets: number[] = [];
      for (const p of parts) {
        if (!/^\d{1,3}$/.test(p) || (p.length > 1 && p.startsWith('0'))) {
          return null;
        }
        const n = Number(p);
        if (!Number.isInteger(n) || n < 0 || n > 255) return null;
        octets.push(n);
      }

      const [a, b] = octets;
      // 0.0.0.0/8 (this network), 10.0.0.0/8 (RFC1918), 127.0.0.0/8 (loopback)
      if (a === 0 || a === 10 || a === 127) return null;
      // 100.64.0.0/10 (CGNAT / Shared Address Space RFC6598)
      if (a === 100 && b >= 64 && b <= 127) return null;
      // 169.254.0.0/16 (link-local / cloud metadata)
      if (a === 169 && b === 254) return null;
      // 172.16.0.0/12 (RFC1918)
      if (a === 172 && b >= 16 && b <= 31) return null;
      // 192.0.0.0/24, 192.0.2.0/24, 192.168.0.0/16
      if (a === 192 && (b === 0 || b === 168)) return null;
      // 198.18.0.0/15 (benchmarking), 198.51.100.0/24 (TEST-NET-2), 203.0.113.0/24 (TEST-NET-3)
      if (a === 198 && (b === 18 || b === 19)) return null;
      const c = octets[2];
      if (a === 198 && b === 51 && c === 100) return null;
      if (a === 203 && b === 0 && c === 113) return null;
      // 224.0.0.0/4 (multicast) & 240.0.0.0/4 (reserved/broadcast)
      if (a >= 224) return null;
    }

    return u;
  } catch {
    return null;
  }
}
