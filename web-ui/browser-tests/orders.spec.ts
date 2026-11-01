import { expect, test } from '@playwright/test';

test('注文の集計を絞り込み、詳細と参照をたどり、チャートとクロス集計を閲覧する', async ({ page, context }) => {
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  page.on('console', (message) => { if (/Content Security Policy|Refused to/.test(message.text())) errors.push(message.text()); });
  await page.goto('/app/');
  await page.getByLabel('接続コード', { exact: true }).fill(process.env.YDE_WEB_CODE!);
  await page.getByRole('button', { name: '接続する', exact: true }).click();
  await expect(page.getByRole('heading', { name: '注文ダッシュボード', exact: true })).toBeVisible();
  await page.getByRole('button', { name: /^顧客別\s/ }).click();
  const table = page.getByRole('table', { name: '業務一覧' });
  const originalRows = await table.locator('tbody tr').count();
  await page.getByRole('combobox', { name: '顧客区分' }).click();
  await page.getByRole('option', { name: '法人', exact: true }).click();
  await page.getByRole('button', { name: '条件を反映', exact: true }).click();
  await expect(table.getByText('個人', { exact: true })).toHaveCount(0);
  await expect(table.getByText('法人', { exact: true }).first()).toBeVisible();
  expect(await table.locator('tbody tr').count()).toBeLessThan(originalRows);
  await page.getByRole('button', { name: '条件をリセット' }).click();
  await expect(table.getByText('個人', { exact: true }).first()).toBeVisible();
  await page.getByRole('button', { name: /^すべての注文\s/ }).click();
  await table.getByRole('button', { name: 'ORD-001', exact: true }).click();
  await expect(page.getByRole('button', { name: '一覧へ戻る' })).toBeVisible();
  await page.getByRole('button', { name: /（CUS-002）/ }).first().click();
  await expect(page.getByRole('heading', { name: 'この対象を参照しているもの' })).toBeVisible();
  await page.screenshot({ path: 'build/browser-results/order-detail.png', fullPage: true });
  await page.goBack();
  await expect(page.getByText('ORD-001', { exact: true }).first()).toBeVisible();
  await page.getByRole('button', { name: '一覧へ戻る' }).click();
  await page.getByRole('button', { name: /^月別売上\s/ }).click();
  await expect(page.getByRole('img', { name: '月別売上' })).toBeVisible();
  await expect(page.getByRole('img', { name: '月別売上' }).locator('svg')).toBeVisible();
  await page.getByText('図の数値を表で確認', { exact: true }).click();
  await expect(page.getByRole('table', { name: '図の数値' })).toBeVisible();
  await page.screenshot({ path: 'build/browser-results/order-dashboard.png', fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: 'build/browser-results/order-mobile.png', fullPage: true });
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.getByRole('button', { name: /^顧客別の順位\s/ }).click();
  const ranking = page.getByRole('img', { name: '顧客別の順位' });
  await expect(ranking.locator('svg')).toBeVisible();
  const numbers = page.getByRole('table', { name: '図の数値' });
  if (!await numbers.isVisible()) await page.getByText('図の数値を表で確認', { exact: true }).click();
  const rows = await numbers.locator('tbody tr').evaluateAll((nodes) => nodes.map((node) => ({
    label: node.querySelectorAll('td')[0].textContent!, value: Number(node.querySelectorAll('td')[1].textContent),
  })));
  expect(rows.map((row) => row.value)).toEqual(rows.map((row) => row.value).sort((a, b) => b - a));
  // SVG の記述順ではなく画面上の上下を検査する。省略されていない名前を数値表の順序と照合する。
  const labels = rows.map((row) => row.label);
  const visibleOrder = await ranking.locator('svg text').evaluateAll((nodes, labels) => nodes
    .filter((node) => labels.includes(node.textContent!))
    .sort((a, b) => a.getBoundingClientRect().top - b.getBoundingClientRect().top)
    .map((node) => node.textContent!), labels);
  expect(visibleOrder.length).toBeGreaterThan(1);
  expect(visibleOrder).toEqual(labels.filter((label) => visibleOrder.includes(label)));
  await page.screenshot({ path: 'build/browser-results/order-ranking.png', fullPage: true });
  await page.getByRole('combobox', { name: '業務ビュー', exact: true }).click();
  await page.getByRole('option', { name: '月×商品カテゴリーの売上', exact: true }).click();
  await expect(page.getByRole('heading', { name: '月×商品カテゴリーの売上', exact: true })).toBeVisible();
  await expect(page.getByRole('table', { name: '業務一覧' })).toBeVisible();
  expect((await context.request.get('/api/v1/workspace')).status()).toBe(404);
  expect(await page.evaluate(() => ({ local: localStorage.length, session: sessionStorage.length }))).toEqual({ local: 0, session: 0 });
  expect(errors).toEqual([]);
  await page.getByRole('button', { name: 'ログアウト', exact: true }).click();
  await expect(page.getByRole('heading', { name: '接続コードを入力' })).toBeVisible();
  await expect(page.getByRole('table')).toHaveCount(0);
});
