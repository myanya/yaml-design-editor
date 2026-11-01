import { afterEach, expect, test, vi } from 'vitest';
import { login, logout, session, workspace } from './api';

afterEach(() => vi.unstubAllGlobals());

test('資格情報を URL に置かず、同じ接続先だけへ送る', async () => {
  const fetch = vi.fn().mockResolvedValue(new Response('{}', { status: 200 }));
  vi.stubGlobal('fetch', fetch);
  await login('使い捨てのコード');
  expect(fetch).toHaveBeenCalledWith('/web-api/session/login', expect.objectContaining({
    method: 'POST', credentials: 'same-origin', mode: 'same-origin', redirect: 'error', cache: 'no-store',
    headers: { 'Content-Type': 'application/json', 'X-YDE-Login': '1' }, body: '{"code":"使い捨てのコード"}',
  }));
  fetch.mockResolvedValue(new Response(null, { status: 204 }));
  await logout('csrf');
  expect(fetch).toHaveBeenLastCalledWith('/web-api/session/logout', expect.objectContaining({ method: 'POST', headers: { 'X-YDE-CSRF': 'csrf' } }));
});

test('失効と停止を区別し、通信失敗を日本語で案内する', async () => {
  const fetch = vi.fn().mockResolvedValue(new Response('{"error":{"code":"unauthorized","message":"接続が失効しました"}}', { status: 401 }));
  vi.stubGlobal('fetch', fetch);
  await expect(session()).rejects.toMatchObject({ status: 401, message: '接続が失効しました' });
  fetch.mockResolvedValue(new Response('', { status: 404 }));
  await expect(workspace()).rejects.toMatchObject({ status: 404 });
  fetch.mockRejectedValue(new TypeError('Failed to fetch'));
  await expect(workspace()).rejects.toMatchObject({ status: 0, message: expect.stringContaining('アプリが起動し') });
});
