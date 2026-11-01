import type { components } from '../build/generated/web-api';

export type Session = components['schemas']['Session'];
export type Workspace = components['schemas']['Workspace'];
export type View = components['schemas']['View'];
export type Row = components['schemas']['Row'];
export type Entity = components['schemas']['Entity'];
export type Chart = components['schemas']['ChartReport'];
export type Dashboard = components['schemas']['DashboardReport'];
export type Reference = components['schemas']['BusinessReference'];
export type ViewRequest = components['schemas']['ViewRequest'];
export type BusinessAction = components['schemas']['BusinessAction'];
export type ActionField = components['schemas']['ActionField'];
export type OperationInput = components['schemas']['OperationInput'];
export type OperationPreview = components['schemas']['OperationPreview'];
export type OperationResult = components['schemas']['OperationResult'];
export type JsonValue = components['schemas']['JsonValue'];
export class ConnectionError extends Error {
  constructor(readonly status: number, message: string, readonly code?: string) { super(message); }
}

// Cookie はブラウザだけが保持する。コード・CSRF を URL や永続ストレージへ書かない。
async function request<T>(path: string, options: RequestInit = {}, timeoutMillis = 10_000): Promise<T> {
  try {
    const signal = options.signal ? AbortSignal.any([options.signal, AbortSignal.timeout(timeoutMillis)]) : AbortSignal.timeout(timeoutMillis);
    const response = await fetch(`/web-api/${path}`, { ...options, signal, credentials: 'same-origin', mode: 'same-origin', cache: 'no-store', redirect: 'error' });
    if (!response.ok) {
      const error = await response.json().catch(() => null) as components['schemas']['Error'] | null;
      throw new ConnectionError(response.status, error?.error?.message ?? '接続できません。デスクトップで Web の稼働状態を確認してください。', error?.error?.code);
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

export async function views(revision: string, signal?: AbortSignal) {
  const items: components['schemas']['Views']['items'] = [];
  let offset: number | null = 0;
  while (offset !== null) {
    const page: components['schemas']['ViewsResponse'] = await request(`views?${new URLSearchParams({ revision, offset: String(offset) })}`, { signal });
    items.push(...page.data.items);
    if (page.data.truncated) throw new ConnectionError(413, 'ビューが多すぎます。デスクトップで定義を確認してください。');
    offset = page.data.nextOffset;
  }
  return items;
}
export const view = (query: ViewRequest, csrf: string, signal?: AbortSignal) => request<components['schemas']['ViewResponse']>('view', {
  method: 'POST', headers: { 'Content-Type': 'application/json', 'X-YDE-CSRF': csrf }, body: JSON.stringify(query), signal,
});
export const entity = (id: string, revision: string, signal?: AbortSignal) => request<components['schemas']['EntityResponse']>(`entity?${new URLSearchParams({ id, revision })}`, { signal });

export async function operations(revision: string, entity: string | null, signal?: AbortSignal) {
  const items: BusinessAction[] = [];
  let offset: number | null = 0;
  while (offset !== null) {
    const query = new URLSearchParams({ revision, offset: String(offset) });
    if (entity) query.set('entity', entity);
    const page: components['schemas']['BusinessActionsResponse'] = await request(`operations?${query}`, { signal });
    items.push(...page.data.items);
    if (page.data.truncated) throw new ConnectionError(413, '操作が多すぎます。デスクトップで確認してください。');
    offset = page.data.nextOffset;
  }
  return items;
}
const operationPost = <T>(path: string, body: unknown, csrf: string) => request<{ data: T }>(`operations/${path}`, {
  method: 'POST', headers: { 'Content-Type': 'application/json', 'X-YDE-CSRF': csrf }, body: JSON.stringify(body),
}, path === 'commit' ? 10_000 : 30_000);
export const prepareOperation = (action: BusinessAction, revision: string, csrf: string) =>
  operationPost<OperationInput>('prepare', { name: action.name, target: action.target ?? undefined, revision }, csrf);
export const planOperation = (inputId: string, params: Record<string, JsonValue>, csrf: string) =>
  operationPost<OperationPreview>('plan', { inputId, params }, csrf);
export const commitOperation = (planId: string, requestId: string, csrf: string) =>
  operationPost<OperationResult>('commit', { planId, requestId }, csrf);
export const operationResult = (id: string) => request<{ data: OperationResult }>(`operations/results/${encodeURIComponent(id)}`);
