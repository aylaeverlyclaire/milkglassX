export interface CfHandlerEnv {
  CF_HANDLER_URL?: string;
  CF_HANDLER_TOKEN?: string;
}

/**
 * Mengembalikan base URL CF handler yang sudah dinormalisasi (tanpa trailing slash),
 * atau `null` jika tidak dikonfigurasi / bukan HTTP(S).
 */
export function getCfHandlerBaseUrl(env: CfHandlerEnv): string | null {
  const base = (env.CF_HANDLER_URL || '').trim().replace(/\/+$/, '');
  if (!base.startsWith('http://') && !base.startsWith('https://')) {
    return null;
  }
  return base;
}

export function isCfHandlerConfigured(env: CfHandlerEnv): boolean {
  return getCfHandlerBaseUrl(env) !== null;
}

export function buildCfHandlerHeaders(
  env: CfHandlerEnv,
  extra?: Record<string, string>
): Record<string, string> {
  const headers: Record<string, string> = {
    Accept: 'application/json',
    ...extra,
  };
  const token = env.CF_HANDLER_TOKEN?.trim();
  if (token) {
    headers['x-auth-token'] = token;
  }
  return headers;
}
