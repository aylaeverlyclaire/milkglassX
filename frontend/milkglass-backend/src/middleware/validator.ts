import { zValidator } from '@hono/zod-validator';
import type { ZodTypeAny } from 'zod';

function formatZodIssues(issues: Array<{ path: Array<string | number>; message: string }>): string {
  const first = issues[0];
  if (!first) return 'Parameter permintaan tidak valid.';
  const field = first.path.length ? first.path.join('.') + ': ' : '';
  return `${field}${first.message}`.slice(0, 200);
}

const hook = (result: any, c: any) => {
  if (!result.success) {
    const msg = formatZodIssues(result.error?.issues ?? []);
    return c.json({ status: 'error', message: msg }, 400);
  }
};

export function zValidatorJson<T extends ZodTypeAny>(schema: T) {
  return zValidator('json', schema, hook);
}

export function zValidatorQuery<T extends ZodTypeAny>(schema: T) {
  return zValidator('query', schema, hook);
}

export function zValidatorParam<T extends ZodTypeAny>(schema: T) {
  return zValidator('param', schema, hook);
}
