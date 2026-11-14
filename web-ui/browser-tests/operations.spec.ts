import { expect, test } from '@playwright/test';

test('TODO の入力と確認を経て保存し、応答が途切れても再適用せず結果を照会する', async ({ page, context }) => {
  const errors: string[] = [];
  const commits: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  page.on('request', (request) => { if (request.url().endsWith('/operations/commit')) commits.push(request.postData()!); });
  await page.goto('/app/');
  await page.getByLabel('接続コード', { exact: true }).fill(process.env.YDE_WEB_CODE!);
  await page.getByRole('button', { name: '接続する', exact: true }).click();
  await page.getByRole('button', { name: /^対応中\s/ }).click();
  await page.getByRole('table', { name: '業務一覧' }).getByRole('button', { name: /TODO-004/ }).click();
  await page.getByRole('button', { name: 'TODOを編集する', exact: true }).click();
  await expect(page.getByLabel('タイトル', { exact: true })).not.toHaveValue('');
  await expect(page.getByText('変更した項目を送ります。', { exact: false })).toBeVisible();
  await expect(page.locator('form').getByText('コメント一覧', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '操作を閉じる', exact: true }).click();
  await page.getByRole('button', { name: 'TODOを完了する', exact: true }).click();
  await expect(page.getByLabel(/^完了日/)).not.toHaveValue('');
  await page.getByLabel(/^完了結果/).fill('Web から完了を確認');
  await page.getByRole('button', { name: '変更内容を確認', exact: true }).click();
  await expect(page.getByRole('button', { name: '適用して保存', exact: true })).toBeEnabled();
  await page.getByLabel(/^完了結果/).fill('確認後に修正した完了結果');
  await expect(page.getByRole('button', { name: '適用して保存', exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: '変更内容を確認', exact: true }).click();
  await expect(page.getByRole('button', { name: '適用して保存', exact: true })).toBeEnabled();
  await page.screenshot({ path: 'build/browser-results/operation-preview.png', fullPage: true });
  // サーバは確定し、ブラウザへの応答だけを失う。次の操作は GET の結果照会でなければならない。
  await page.route('**/web-api/operations/commit', async (route) => {
    const response = await route.fetch();
    expect(response.ok()).toBe(true);
    await route.abort('failed');
  }, { times: 1 });
  await page.getByRole('button', { name: '適用して保存', exact: true }).click();
  await expect(page.getByText('保存結果を確認してください。', { exact: false })).toBeVisible();
  await page.getByRole('button', { name: '結果を照会', exact: true }).click();
  await expect(page.getByText('保存しました。', { exact: false })).toBeVisible();
  expect(commits).toHaveLength(1);
  await page.getByRole('button', { name: '操作を閉じる', exact: true }).click();
  await expect(page.getByRole('button', { name: 'TODOを完了する', exact: true })).toBeDisabled();
  await expect(page.getByText('確認後に修正した完了結果', { exact: true })).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  try { await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true); }
  finally { await page.screenshot({ path: 'build/browser-results/operation-mobile.png', fullPage: true }); }
  expect((await context.request.post('/api/v1/operations/commit')).status()).toBe(404);
  expect(errors).toEqual([]);
});
