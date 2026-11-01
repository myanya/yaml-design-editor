import { readdir, writeFile } from 'node:fs/promises';
import { join, relative } from 'node:path';

// 配信できる同梱資源をビルド時に確定し、任意の classpath やファイルへ経路を広げない。
const root = 'build/dist';
async function scan(directory) {
  const result = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) result.push(...await scan(path));
    else if (entry.isFile() && /\.(html|js|css|svg|png|woff2)$/.test(entry.name)) result.push(relative(root, path).replaceAll('\\', '/'));
  }
  return result;
}
await writeFile(join(root, 'assets.txt'), (await scan(root)).sort().join('\n') + '\n');
