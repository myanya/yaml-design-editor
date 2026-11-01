import { readFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';

// Windows の Java → Node 間で、JSON 内の引用符をコマンドラインとして解釈させない。
// Inspector 本体をそのまま実行し、引数の受渡しだけをファイルにする。
const [inspector, argumentsFile] = process.argv.slice(2);
const args = JSON.parse(await readFile(argumentsFile, 'utf8'));
process.argv = [process.execPath, inspector, ...args];
await import(pathToFileURL(inspector).href);
