import { expect, test } from '@playwright/test';

test('未対応ビューの名前と理由を示し、定義不足と区別する', async ({ page }) => {
  const requested: string[] = [];
  page.on('request', (request) => {
    if (request.url().endsWith('/web-api/view')) requested.push(request.postDataJSON().id);
  });
  await page.goto('/app/');
  await page.getByLabel('接続コード', { exact: true }).fill(process.env.YDE_WEB_CODE!);
  await page.getByRole('button', { name: '接続する', exact: true }).click();
  await expect(page.getByText('デスクトップで表示するビュー', { exact: true })).toBeVisible();
  await expect(page.getByText('関係のレンズ: この種類のビューは Web では表示できません。デスクトップで開いてください。', { exact: true })).toBeVisible();
  if (process.env.YDE_WEB_SCENARIO === 'mixed') {
    await expect(page.getByRole('heading', { name: 'TODOダッシュボード', exact: true })).toBeVisible();
    await page.getByRole('combobox', { name: '業務ビュー' }).click();
    await expect(page.getByRole('option', { name: '関係のレンズ（デスクトップで表示）', exact: true })).toHaveAttribute('aria-disabled', 'true');
    await page.keyboard.press('Escape');
  } else {
    await expect(page.getByText('このワークスペースのビューはデスクトップで開いてください。', { exact: true })).toBeVisible();
    await expect(page.getByRole('combobox', { name: '業務ビュー' })).toHaveAttribute('aria-disabled', 'true');
    expect(requested).toEqual([]);
  }
  await page.goto('/app/?view=desktop-lens');
  await expect(page.getByText('選択したビューは Web で表示できません。別のビューを選ぶか、デスクトップで開いてください。', { exact: true })).toBeVisible();
  expect(requested).not.toContain('desktop-lens');
  await page.screenshot({ path: `build/browser-results/${process.env.YDE_WEB_SCENARIO}-views.png`, fullPage: true });
});
