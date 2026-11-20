export type D1ReadClient = Pick<D1Database, 'prepare' | 'batch'>;

/**
 * Mengembalikan D1DatabaseSession ('first-unconstrained') untuk query baca agar
 * memanfaatkan D1 Global Read Replication (APAC). Fallback aman ke env.DB pada mock/runtime lama.
 */
export function readDb(env: { DB: D1Database }): D1ReadClient {
  const withSession = (
    env.DB as D1Database & {
      withSession?: (constraint?: string) => D1ReadClient;
    }
  ).withSession;
  return typeof withSession === 'function' ? withSession.call(env.DB, 'first-unconstrained') : env.DB;
}
