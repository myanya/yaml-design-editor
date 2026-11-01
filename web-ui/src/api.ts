import type { components } from '../build/generated/web-api';

export type Session = components['schemas']['Session'];
export type Workspace = components['schemas']['Workspace'];
export class ConnectionError extends Error {
  constructor(readonly status: number, message: string) { super(message); }
}

// Cookie はブラウザだけが保持する。コード・CSRF を URL や永続ストレージへ書かない。
async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  try {
    const signal = options.signal ? AbortSignal.any([options.signal, AbortSignal.timeout(10_000)]) : AbortSignal.timeout(10_000);
    const response = await fetch(`/web-api/${path}`, { ...options, signal, credentials: 'same-origin', mode: 'same-origin', cache: 'no-store', redirect: 'error' });
    if (!response.ok) {
      const error = await response.json().catch(() => null) as components['schemas']['Error'] | null;
      throw new ConnectionError(response.status, error?.error?.message ?? '接続できません。デスクトップで Web の稼働状態を確認してください。');
    }
    if (response.status === 204) return undefined as T;
    return await response.json() as T;
  } catch (error) {
    if (error instanceof ConnectionError || options.signal?.aborted) throw error;
    throw new ConnectionError(0, 'YDE に接続できません。アプリが起動し、Web が稼働中か確認してください。');
  }
}
export const session = (signal?: AbortSignal) => request<Session>('session', { signal });
export const workspace = (signal?: AbortSignal) => request<Workspace>('workspace', { signal });
export const login = (code: string) => request<Session>('session/login', {
  method: 'POST', headers: { 'Content-Type': 'application/json', 'X-YDE-Login': '1' }, body: JSON.stringify({ code }),
});
export const logout = (csrf: string) => request<void>('session/logout', { method: 'POST', headers: { 'X-YDE-CSRF': csrf } });
