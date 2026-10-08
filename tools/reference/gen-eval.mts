// Runs the ORIGINAL TypeScript solo evaluator (opt/evaluate.ts) and party evaluator (opt/party-evaluate.ts) over the
// caster heroes in src/test/resources/reference/eval-input.json and writes their results to eval-expected.json.
// EvaluatorParityTest builds the same heroes through the Java content layer and must reproduce the numbers.
//
//   DND_APP_DIR=/path/to/dnd-app node --experimental-transform-types --import ./tools/reference/register-ts.mjs \
//     tools/reference/gen-eval.mts
//
// Heroes are built by the real catalog (opt/catalog.ts `casterPackageFor`) through the real `buildFromGenome`, so the
// test also proves the Java recipe-built hero equals the upstream genome-built one. Upstream baseline:
// Zr0AM/dnd-app @ 8db9df32179604057009203c9effa5bc91c9dd6f.
import { readFileSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const appDir = process.env.DND_APP_DIR ?? '/home/user/dnd-app';
const load = (rel: string) => import(pathToFileURL(join(appDir, 'sim/src', rel)).href);
const { buildSeedDatabase } = await load('content/load-db.ts');
const { loadMartialCatalog } = await load('opt/catalog.ts');
const { evaluate } = await load('opt/evaluate.ts');
const { evaluatePartyBuild, loadPartyHarness } = await load('opt/party-evaluate.ts');

const root = resolve(import.meta.dirname, '../../src/test/resources/reference');
const input = JSON.parse(readFileSync(join(root, 'eval-input.json'), 'utf8'));
const db = buildSeedDatabase();

const catalogs = new Map<number, any>();
const harnesses = new Map<number, any>();
const catalogFor = (level: number) => {
  if (!catalogs.has(level)) catalogs.set(level, loadMartialCatalog(db, level));
  return catalogs.get(level);
};
const harnessFor = (level: number) => {
  if (!harnesses.has(level)) harnesses.set(level, loadPartyHarness(db, level));
  return harnesses.get(level);
};

const out: any[] = [];
for (const h of input.heroes) {
  const genome = {
    classSlug: h.class,
    abilityAssignment: h.assignment,
    weaponName: 'Dagger',
    armorName: null,
    shield: false,
    twoHanded: false,
  };
  const solo = evaluate(genome, catalogFor(h.level), { runs: input.soloRuns });
  const party = evaluatePartyBuild(genome, catalogFor(h.level), harnessFor(h.level), {
    runs: input.partyRuns,
    heroRole: h.role,
  });
  out.push({ name: h.name, solo, party });
  console.log(h.name.padEnd(14), 'solo win', solo.winRate.toFixed(3), 'dmg', solo.avgDamageDealt.toFixed(1), '| party win', party.winRate.toFixed(3), 'support', party.avgHeroSupport.toFixed(2));
}
writeFileSync(
  join(root, 'eval-expected.json'),
  JSON.stringify({ source: 'Zr0AM/dnd-app@8db9df32179604057009203c9effa5bc91c9dd6f', results: out }) + '\n',
);
console.log('wrote eval-expected.json');
