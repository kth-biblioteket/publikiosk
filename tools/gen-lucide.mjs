// Genererar app/src/main/assets/lucide-icons.txt ur lucide-react (samma version som publicomtools och Linux-kiosken).
// Körs: node tools/gen-lucide.mjs <sökväg till node_modules/lucide-react>
// Format, en rad per namn: "<namn>\t<json>" där json är ikonens noder [["path",{"d":"…"}],…] (utan key),
// eller "<alias>\t@<namn>" när namnet är ett alias för en annan ikon.
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const root = process.argv[2];
if (!root) { console.error('ange sökvägen till lucide-react'); process.exit(1); }
const pkg = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));
const esm = path.join(root, 'dist', 'esm');
const dyn = fs.readFileSync(path.join(esm, 'dynamicIconImports.mjs'), 'utf8');
const names = [...dyn.matchAll(/^\s*"([^"]+)":\s*\(\)\s*=>\s*import\('\.\/icons\/([^']+)\.mjs'\)/gm)].map(m => [m[1], m[2]]);
const lines = [];
const canonical = new Set();
for (const [name, file] of names) {
  if (name !== file) { lines.push(`${name}\t@${file}`); continue; }
  const mod = await import(pathToFileURL(path.join(esm, 'icons', file + '.mjs')).href);
  const nodes = mod.__iconData.node.map(([tag, attrs]) => { const a = { ...attrs }; delete a.key; return [tag, a]; });
  lines.push(`${name}\t${JSON.stringify(nodes)}`);
  canonical.add(name);
}
lines.sort();
const out = `# lucide-react ${pkg.version} (ISC), genererad av tools/gen-lucide.mjs. En rad per ikon: namn<TAB>json, eller namn<TAB>@annatnamn (alias).\n` + lines.join('\n') + '\n';
fs.writeFileSync('app/src/main/assets/lucide-icons.txt', out);
fs.copyFileSync(path.join(root, 'LICENSE'), 'docs/licenses/Lucide-ISC.txt');
console.log(`${pkg.version}: ${names.length} namn (${canonical.size} ikoner, ${names.length - canonical.size} alias), ${out.length} byte`);
