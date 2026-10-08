// Generates src/test/resources/reference/rng.json from the TypeScript sim, so the Java
// RNG/dice port can be checked against real output of the original implementation.
//
//   DND_APP_DIR=/path/to/dnd-app node --experimental-strip-types tools/reference/gen-rng.mts
//
// Upstream baseline: Zr0AM/dnd-app @ 8db9df32179604057009203c9effa5bc91c9dd6f.
import { writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const appDir = process.env.DND_APP_DIR ?? '/home/user/dnd-app';
const load = (rel: string) => import(pathToFileURL(join(appDir, 'sim/src', rel)).href);
const { Random, deriveSeed, seedFrom } = await load('rng/rng.ts');
const { roll, dice, rollD20 } = await load('dice/dice.ts');

const roots = [0, 1, 7, 42, 12345, 2 ** 31, 0xffffffff];
const labels = ['', 'a', 'enemy:goblin-1:attack', 'hero:fireball:dmg', 'naïve☃😀'];

const deriveSeedCases = roots.flatMap((root) =>
  labels.map((label) => ({ root, label, value: deriveSeed(root, label) })),
);

const seedFromCases = [
  ['l3-pair-goblins', 0],
  ['l3-pair-goblins', 1],
  ['scenario-a', 'family', 3],
  ['party', 'R6', 'horde', 11],
  ['day', 3, 2],
].map((parts) => ({ parts, value: seedFrom(...parts) }));

const take = (rng: () => number, n: number) => Array.from({ length: n }, () => rng());
const streamCases = [
  { root: 12345, label: 'a' },
  { root: 99, label: 'enemy:attack' },
  { root: 0xffffffff, label: 'naïve☃😀' },
].map((c) => ({ ...c, first: take(new Random(c.root).stream(c.label), 25) }));

const childCases = [{ root: 11, child: 'enemy:goblin-1', label: 'attack' }].map((c) => ({
  ...c,
  first: take(new Random(c.root).child(c.child).stream(c.label), 10),
}));

const diceRolls = (adv: string) => {
  const r = new Random(2024).stream('d20');
  return Array.from({ length: 40 }, () => rollD20(r, adv));
};
const dice8d6 = (() => {
  const r = new Random(2024).stream('test');
  return Array.from({ length: 20 }, () => roll(r, dice(8, 6, 2)));
})();

const out = {
  source: 'Zr0AM/dnd-app@8db9df32179604057009203c9effa5bc91c9dd6f',
  deriveSeed: deriveSeedCases,
  seedFrom: seedFromCases,
  streams: streamCases,
  child: childCases,
  dice: {
    seed: 2024,
    d20Label: 'd20',
    d20: { normal: diceRolls('normal'), advantage: diceRolls('advantage'), disadvantage: diceRolls('disadvantage') },
    eightD6Plus2: { label: 'test', rolls: dice8d6 },
  },
};
const dest = resolve(import.meta.dirname, '../../src/test/resources/reference/rng.json');
writeFileSync(dest, JSON.stringify(out, null, 2) + '\n');
console.log('wrote', dest);
