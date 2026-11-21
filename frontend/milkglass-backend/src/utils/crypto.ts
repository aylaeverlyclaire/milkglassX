const utf8Encoder = new TextEncoder();

export function bytesToHex(bytes: Uint8Array): string {
  let out = '';
  for (let i = 0; i < bytes.length; i++) {
    out += bytes[i].toString(16).padStart(2, '0');
  }
  return out;
}

export async function sha256Hex(input: string, sliceBytes?: number): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', utf8Encoder.encode(input));
  const arr = new Uint8Array(digest);
  return bytesToHex(sliceBytes !== undefined ? arr.slice(0, sliceBytes) : arr);
}

export async function sha1Hex(input: string, sliceBytes?: number): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-1', utf8Encoder.encode(input));
  const arr = new Uint8Array(digest);
  return bytesToHex(sliceBytes !== undefined ? arr.slice(0, sliceBytes) : arr);
}

export function timingSafeEqualHex(a: string, b: string): boolean {
  if (a.length !== b.length || a.length === 0) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) {
    diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  }
  return diff === 0;
}
