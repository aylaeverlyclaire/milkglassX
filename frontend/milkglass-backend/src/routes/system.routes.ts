import { Hono } from 'hono';
import { zValidatorJson } from '../middleware/validator';
import { z } from 'zod';
import { rateLimit, hashClientIp } from '../middleware/rateLimit';
import { AppBindings } from '../index';

export const systemRoutes = new Hono<{ Bindings: AppBindings }>();

const DEFAULT_TELEMETRY_REPO = 'aylaeverlyclaire/milkglassX';
const CRASH_ISSUE_DEDUP_SEC = 6 * 3600;
const PERF_ISSUE_DEDUP_SEC = 24 * 3600;

systemRoutes.get('/health', (c) => {
  return c.json({
    status: 'ok',
    service: 'milkglass-backend',
    resolution_mode: 'CLIENT_CASCADE_MKV',
  });
});

const telemetryBodySchema = z.object({
  source_id: z.number().int().positive(),
  reason: z.enum(['http_403', 'expired', 'not_found', 'timeout', 'codec', 'dns']),
});

systemRoutes.post(
  '/telemetry/source',
  rateLimit(10, 60),
  zValidatorJson(telemetryBodySchema),
  async (c) => {
    const { source_id, reason } = c.req.valid('json');

    if (reason === 'timeout' || reason === 'dns' || reason === 'codec') {
      return c.json({ status: 'ok', recorded: false });
    }

    const clientIp = c.req.header('CF-Connecting-IP') || '0.0.0.0';
    const ipHash = await hashClientIp(clientIp);
    const nowSec = Math.floor(Date.now() / 1000);

    const result = await c.env.DB.prepare(
      `INSERT OR IGNORE INTO source_telemetry_events (source_id, reason, ip_hash, created_at)
       SELECT ?1, ?2, ?3, ?4
       WHERE EXISTS (
         SELECT 1 FROM stream_sources WHERE id = ?1 AND is_active = 1
       )
       AND (
         SELECT COUNT(*) FROM source_telemetry_events WHERE source_id = ?1
       ) < 10`
    )
      .bind(source_id, reason, ipHash, nowSec)
      .run();

    const recorded = (result.meta?.changes ?? 0) > 0;
    return c.json({ status: 'ok', recorded });
  }
);

const crashReportSchema = z.object({
  exception_type: z.string().trim().min(1).max(200),
  exception_message: z.string().trim().max(1000).optional().default(''),
  stack_trace: z.string().trim().min(1).max(12000),
  thread_name: z.string().trim().max(120).optional().default(''),
  app_version: z.string().trim().max(64).optional().default(''),
  version_code: z.number().int().min(0).max(1_000_000_000).optional().default(0),
  device_model: z.string().trim().max(120).optional().default(''),
  android_version: z.string().trim().max(64).optional().default(''),
  screen: z.string().trim().max(120).optional().default(''),
  is_fatal: z.boolean().optional().default(true),
});

async function sha256ShortHex(input: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(input));
  return Array.from(new Uint8Array(digest))
    .slice(0, 12)
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');
}

async function computeCrashFingerprint(exceptionType: string, stackTrace: string): Promise<string> {
  const topFrames = stackTrace
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.startsWith('at '))
    .slice(0, 4)
    .join('|');
  const raw = `${exceptionType.trim()}|${topFrames || stackTrace.slice(0, 240)}`;
  return sha256ShortHex(raw);
}

async function createGitHubIssue(env: AppBindings, title: string, body: string): Promise<string> {
  const token = env.GITHUB_TELEMETRY_TOKEN?.trim();
  if (!token) return '';
  const repo = (env.GITHUB_TELEMETRY_REPO?.trim() || DEFAULT_TELEMETRY_REPO).replace(/^\/+|\/+$/g, '');
  try {
    const resp = await fetch(`https://api.github.com/repos/${repo}/issues`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${token}`,
        Accept: 'application/vnd.github+json',
        'Content-Type': 'application/json',
        'User-Agent': 'milkglass-backend-telemetry/1.0',
        'X-GitHub-Api-Version': '2022-11-28',
      },
      body: JSON.stringify({ title: title.slice(0, 220), body }),
      signal: AbortSignal.timeout(6_000),
    });
    if (!resp.ok) return '';
    const data = (await resp.json()) as { html_url?: string };
    return typeof data?.html_url === 'string' ? data.html_url : '';
  } catch {
    return '';
  }
}

async function dispatchGitHubCrashIssue(
  env: AppBindings,
  report: z.infer<typeof crashReportSchema>,
  fingerprint: string,
  reportId: number
): Promise<string> {
  const shortClass = report.exception_type.split('.').pop() || report.exception_type;
  const shortMsg = report.exception_message
    ? `: ${report.exception_message.replace(/\s+/g, ' ').slice(0, 120)}`
    : '';
  const severity = report.is_fatal ? 'FATAL' : 'NON-FATAL';
  const issueTitle = `[MilkglassCrash] ${severity} ${shortClass}${shortMsg}`;
  const issueBody = [
    `### Automated Milkglass Client Crash Report`,
    `- **Severity**: \`${severity}\``,
    `- **Exception**: \`${report.exception_type}\``,
    `- **Message**: ${report.exception_message || '-'}`,
    `- **Thread**: \`${report.thread_name || '-'}\``,
    `- **Screen**: \`${report.screen || '-'}\``,
    `- **App Version**: ${report.app_version || '-'}${report.version_code ? ` (${report.version_code})` : ''}`,
    `- **Device / Android**: ${report.device_model || '-'} / Android ${report.android_version || '-'}`,
    `- **Fingerprint**: \`${fingerprint}\``,
    '',
    '#### Stack Trace',
    '```text',
    report.stack_trace,
    '```',
  ].join('\n');

  const url = await createGitHubIssue(env, issueTitle, issueBody);
  if (url && reportId > 0) {
    await env.DB.prepare('UPDATE crash_reports SET github_issue_url = ?1 WHERE id = ?2')
      .bind(url, reportId)
      .run()
      .catch(() => {});
  }
  return url;
}

systemRoutes.post(
  '/crash-report',
  rateLimit(10, 60),
  zValidatorJson(crashReportSchema),
  async (c) => {
    const report = c.req.valid('json');
    const nowSec = Math.floor(Date.now() / 1000);
    const clientIp = c.req.header('CF-Connecting-IP') || '0.0.0.0';
    const ipHash = await hashClientIp(clientIp);
    const fingerprint = await computeCrashFingerprint(report.exception_type, report.stack_trace);

    let reportId = 0;
    try {
      const insert = await c.env.DB.prepare(
        `INSERT INTO crash_reports (
           fingerprint, exception_type, exception_message, stack_trace, thread_name,
           app_version, version_code, device_model, android_version, screen,
           is_fatal, ip_hash, created_at
         ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13)`
      )
        .bind(
          fingerprint,
          report.exception_type,
          report.exception_message,
          report.stack_trace,
          report.thread_name,
          report.app_version,
          report.version_code,
          report.device_model,
          report.android_version,
          report.screen,
          report.is_fatal ? 1 : 0,
          ipHash,
          nowSec
        )
        .run();
      reportId = Number(insert.meta?.last_row_id || 0);
    } catch {
      // Keep crash-report resilient even if migration hasn't been applied yet
    }

    let issueScheduled = false;
    if (c.env.GITHUB_TELEMETRY_TOKEN?.trim()) {
      try {
        const recentIssue = await c.env.DB.prepare(
          `SELECT id FROM crash_reports
           WHERE fingerprint = ?1 AND github_issue_url != '' AND created_at >= ?2
           LIMIT 1`
        )
          .bind(fingerprint, nowSec - CRASH_ISSUE_DEDUP_SEC)
          .first<{ id: number }>()
          .catch(() => null);

        if (!recentIssue) {
          issueScheduled = true;
          c.executionCtx.waitUntil(
            dispatchGitHubCrashIssue(c.env, report, fingerprint, reportId)
          );
        }
      } catch {
        // Non-fatal
      }
    }

    return c.json({
      status: 'ok',
      stored: reportId > 0,
      report_id: reportId,
      fingerprint,
      issue_scheduled: issueScheduled,
    });
  }
);

systemRoutes.get('/crash-report', rateLimit(30, 60), async (c) => {
  const adminSecret = c.req.header('X-Admin-Secret') || '';
  if (!c.env.ADMIN_SECRET || adminSecret !== c.env.ADMIN_SECRET) {
    return c.json({ status: 'error', message: 'Akses ditolak.' }, 401);
  }
  const limit = Math.min(Math.max(Number(c.req.query('limit') || '20'), 1), 100);
  const rows = await c.env.DB.prepare(
    `SELECT id, fingerprint, exception_type, exception_message, thread_name,
            app_version, version_code, device_model, android_version, screen,
            is_fatal, github_issue_url, created_at
     FROM crash_reports
     ORDER BY created_at DESC
     LIMIT ?1`
  )
    .bind(limit)
    .all();
  return c.json({ status: 'ok', data: rows.results || [] });
});

const perfReportSchema = z.object({
  app_version: z.string().trim().max(64).optional().default(''),
  version_code: z.number().int().min(0).max(1_000_000_000).optional().default(0),
  device_model: z.string().trim().min(1).max(120),
  android_version: z.string().trim().max(64).optional().default(''),
  startup_ms: z.number().int().min(0).max(120_000).optional().default(0),
  avg_ttff_ms: z.number().int().min(0).max(120_000).optional().default(0),
  max_ttff_ms: z.number().int().min(0).max(120_000).optional().default(0),
  play_attempts: z.number().int().min(0).max(100_000).optional().default(0),
  play_failures: z.number().int().min(0).max(100_000).optional().default(0),
  crash_count: z.number().int().min(0).max(10_000).optional().default(0),
  memory_peak_mb: z.number().int().min(0).max(65_536).optional().default(0),
  is_stable: z.boolean().optional().default(false),
  consecutive_stable_days: z.number().int().min(0).max(3650).optional().default(0),
});

async function dispatchGitHubPerfIssue(
  env: AppBindings,
  report: z.infer<typeof perfReportSchema>,
  deviceKey: string,
  reportId: number
): Promise<string> {
  const reasons: string[] = [];
  if (report.crash_count > 0) reasons.push(`crashes=${report.crash_count}`);
  if (report.play_failures > 0) reasons.push(`play_failures=${report.play_failures}/${report.play_attempts}`);
  if (report.startup_ms > 1500) reasons.push(`startup=${report.startup_ms}ms`);
  if (report.avg_ttff_ms > 1200) reasons.push(`avg_ttff=${report.avg_ttff_ms}ms`);
  const summary = reasons.length > 0 ? reasons.join(', ') : 'degraded';

  const issueTitle = `[MilkglassPerf] Daily Degraded (${report.device_model}): ${summary}`;
  const issueBody = [
    `### Automated Milkglass Daily Performance Report`,
    `- **Status**: \`UNSTABLE / DEGRADED\``,
    `- **Reasons**: ${summary}`,
    `- **App Version**: ${report.app_version || '-'}${report.version_code ? ` (${report.version_code})` : ''}`,
    `- **Device / Android**: ${report.device_model} / Android ${report.android_version || '-'}`,
    `- **Device Key**: \`${deviceKey}\``,
    `- **Startup Time**: ${report.startup_ms} ms`,
    `- **Avg / Max TTFF**: ${report.avg_ttff_ms} ms / ${report.max_ttff_ms} ms`,
    `- **Playback**: ${report.play_attempts} attempts, ${report.play_failures} failures`,
    `- **Crashes (24h)**: ${report.crash_count}`,
    `- **Memory Peak**: ${report.memory_peak_mb} MB`,
  ].join('\n');

  const url = await createGitHubIssue(env, issueTitle, issueBody);
  if (url && reportId > 0) {
    await env.DB.prepare('UPDATE perf_reports SET github_issue_url = ?1 WHERE id = ?2')
      .bind(url, reportId)
      .run()
      .catch(() => {});
  }
  return url;
}

systemRoutes.post(
  '/perf-report',
  rateLimit(10, 60),
  zValidatorJson(perfReportSchema),
  async (c) => {
    const report = c.req.valid('json');
    const nowSec = Math.floor(Date.now() / 1000);
    const clientIp = c.req.header('CF-Connecting-IP') || '0.0.0.0';
    const ipHash = await hashClientIp(clientIp);
    const deviceKey = await sha256ShortHex(`${report.device_model}|${report.android_version}|${ipHash}`);

    let reportId = 0;
    try {
      const insert = await c.env.DB.prepare(
        `INSERT INTO perf_reports (
           device_key, app_version, version_code, device_model, android_version,
           startup_ms, avg_ttff_ms, max_ttff_ms, play_attempts, play_failures,
           crash_count, memory_peak_mb, is_stable, consecutive_stable_days, created_at
         ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15)`
      )
        .bind(
          deviceKey,
          report.app_version,
          report.version_code,
          report.device_model,
          report.android_version,
          report.startup_ms,
          report.avg_ttff_ms,
          report.max_ttff_ms,
          report.play_attempts,
          report.play_failures,
          report.crash_count,
          report.memory_peak_mb,
          report.is_stable ? 1 : 0,
          report.consecutive_stable_days,
          nowSec
        )
        .run();
      reportId = Number(insert.meta?.last_row_id || 0);
    } catch {
      // Non-fatal
    }

    let issueScheduled = false;
    if (!report.is_stable && c.env.GITHUB_TELEMETRY_TOKEN?.trim()) {
      try {
        const recentIssue = await c.env.DB.prepare(
          `SELECT id FROM perf_reports
           WHERE device_key = ?1 AND version_code = ?2 AND github_issue_url != '' AND created_at >= ?3
           LIMIT 1`
        )
          .bind(deviceKey, report.version_code, nowSec - PERF_ISSUE_DEDUP_SEC)
          .first<{ id: number }>()
          .catch(() => null);

        if (!recentIssue) {
          issueScheduled = true;
          c.executionCtx.waitUntil(
            dispatchGitHubPerfIssue(c.env, report, deviceKey, reportId)
          );
        }
      } catch {
        // Non-fatal
      }
    }

    return c.json({
      status: 'ok',
      stored: reportId > 0,
      report_id: reportId,
      is_stable: report.is_stable,
      issue_scheduled: issueScheduled,
    });
  }
);

systemRoutes.get('/perf-report', rateLimit(30, 60), async (c) => {
  const adminSecret = c.req.header('X-Admin-Secret') || '';
  if (!c.env.ADMIN_SECRET || adminSecret !== c.env.ADMIN_SECRET) {
    return c.json({ status: 'error', message: 'Akses ditolak.' }, 401);
  }
  const limit = Math.min(Math.max(Number(c.req.query('limit') || '20'), 1), 100);
  const rows = await c.env.DB.prepare(
    `SELECT id, device_key, app_version, version_code, device_model, android_version,
            startup_ms, avg_ttff_ms, max_ttff_ms, play_attempts, play_failures,
            crash_count, memory_peak_mb, is_stable, consecutive_stable_days,
            github_issue_url, created_at
     FROM perf_reports
     ORDER BY created_at DESC
     LIMIT ?1`
  )
    .bind(limit)
    .all();
  return c.json({ status: 'ok', data: rows.results || [] });
});
