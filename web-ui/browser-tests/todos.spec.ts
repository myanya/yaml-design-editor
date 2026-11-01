import { expect, test } from '@playwright/test';

test('TODO の基準日を変え、古い保存版の応答から再取得できる', async ({ page, context }) => {
  await page.goto('/app/');
  await page.getByLabel('接続コード', { exact: true }).fill(process.env.YDE_WEB_CODE!);
  await page.getByRole('button', { name: '接続する', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'TODOダッシュボード', exact: true })).toBeVisible();
  await page.route('**/web-api/view', (route) => route.fulfill({ status: 503, contentType: 'application/json',
    body: JSON.stringify({ error: { code: 'busy', message: '読み取りが混み合っています。再試行してください' } }) }), { times: 1 });
  await page.getByLabel(/^基準日/).fill('1900-01-01');
  await page.getByRole('button', { name: '条件を反映', exact: true }).click();
  await expect(page.getByRole('button', { name: /^期限超過\s+0 件$/ })).toBeVisible();
  await expect(page.getByText('該当する対象はありません。', { exact: true })).toBeVisible();
  // 最初の条件変更だけ、保存版が競合した状況にする。手動再取得で同じ版でも再試行できる。
  await page.route('**/web-api/view', (route) => route.fulfill({ status: 409, contentType: 'application/json',
    body: JSON.stringify({ error: { code: 'stale_revision', message: '保存済みの版が変わりました。最初から取得してください' } }) }), { times: 1 });
  await page.getByLabel(/^基準日/).fill('2099-01-01');
  await page.getByRole('button', { name: '条件を反映', exact: true }).click();
  await expect(page.getByText('保存済みの版が変わりました。最初から取得してください', { exact: true })).toBeVisible();
  await expect(page.getByRole('table', { name: '業務一覧' })).toHaveCount(0);
  await page.getByRole('button', { name: '再取得', exact: true }).click();
  await expect(page.getByRole('button', { name: /^期限超過\s+[1-9][0-9]* 件$/ })).toBeVisible();
  await expect(page.getByRole('table', { name: '業務一覧' })).toBeVisible();
  await page.screenshot({ path: 'build/browser-results/todo-dashboard.png', fullPage: true });
  const session = await (await context.request.get('/web-api/session')).json();
  await context.request.post('/web-api/session/logout', { headers: { Origin: process.env.YDE_WEB_URL!, 'X-YDE-CSRF': session.csrfToken } });
  await expect(page.getByRole('heading', { name: '接続コードを入力' })).toBeVisible({ timeout: 10_000 });
  await expect(page.getByRole('table')).toHaveCount(0);
});
