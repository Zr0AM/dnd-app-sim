// Dumps what the TypeScript content layer produces from the seed database, so the Java port can be compared with it:
//   - a SHA-256 of the canonical JSON of EVERY compiled monster template (all 341), plus three readable examples
//   - every weapon and armor, every class, class progression and spell slots for all 12 classes at levels 1-19
//   - the XP-by-challenge-rating table
//
//   DND_APP_DIR=/path/to/dnd-app node --experimental-strip-types --import ./tools/reference/register-ts.mjs \
//     tools/reference/gen-content.mts
//
// Upstream baseline: Zr0AM/dnd-app @ 8db9df32179604057009203c9effa5bc91c9dd6f.
import { createHash } from 'node:crypto';
import { writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const appDir = process.env.DND_APP_DIR ?? '/home/user/dnd-app';
const load = (rel: string) => import(pathToFileURL(join(appDir, 'sim/src', rel)).href);
const db = await load('content/load-db.ts');
const { compileMonster } = await load('content/monster.ts');
const { multiattackFor } = await load('content/multiattack.ts');

/** JSON with recursively sorted keys; undefined-valued keys are omitted (as JSON.stringify does). */
function canon(v: any): string {
  if (v === null) return 'null';
  if (Array.isArray(v)) return '[' + v.map(canon).join(',') + ']';
  if (typeof v === 'object')
    return (
      '{' +
      Object.keys(v)
        .filter((k) => v[k] !== undefined)
        .sort()
        .map((k) => JSON.stringify(k) + ':' + canon(v[k]))
        .join(',') +
      '}'
    );
  return JSON.stringify(v);
}
const sha256 = (s: string) => createHash('sha256').update(s).digest('hex');

const database = db.buildSeedDatabase();
const CLASSES = ['fighter', 'barbarian', 'rogue', 'ranger', 'paladin', 'monk', 'wizard', 'cleric', 'bard', 'sorcerer', 'warlock', 'druid'];

// ---- monsters
const sources = db.loadMonsterSources(database);
const templates = sources.map((s: any) => compileMonster(s, multiattackFor(s.monster.monsterSlug)));
const monsterHashes: Record<string, string> = {};
for (const t of templates) monsterHashes[t.slug] = sha256(canon(t));
const examples = Object.fromEntries(
  ['goblin-minion', 'troll', 'young-red-dragon'].map((slug) => [slug, JSON.parse(canon(templates.find((t: any) => t.slug === slug)))]),
);

// ---- equipment
const names = (sql: string) => (database.prepare(sql).all() as any[]).map((r) => r.n as string);
const weapons = names('SELECT e.equipmentName n FROM Weapon w JOIN Equipment e USING (equipmentID) ORDER BY e.equipmentName').map((n) => db.loadWeapon(database, n));
const armors = names('SELECT e.equipmentName n FROM Armor a JOIN Equipment e USING (equipmentID) ORDER BY e.equipmentName').map((n) => db.loadArmor(database, n));

// ---- classes, progression, slots
const classes = CLASSES.map((slug) => db.loadClass(database, slug));
const progression: Record<string, any> = {};
const slots: Record<string, any> = {};
for (const slug of CLASSES) {
  for (let level = 1; level <= 19; level++) {
    progression[`${slug}@${level}`] = db.loadProgression(database, slug, level);
    slots[`${slug}@${level}`] = db.loadSpellSlots(database, slug, level);
  }
}
const xp = Object.fromEntries((database.prepare('SELECT crValue, xp FROM ChallengeRating').all() as any[]).map((r) => [String(r.crValue), r.xp]));

database.close();
const out = {
  source: 'Zr0AM/dnd-app@8db9df32179604057009203c9effa5bc91c9dd6f',
  monsterCount: templates.length,
  monsterWithAttacks: templates.filter((t: any) => t.attacks.length > 0).length,
  monsterHashes,
  examples,
  weapons,
  armors,
  classes,
  progression,
  slots,
  xp,
};
const dest = resolve(import.meta.dirname, '../../src/test/resources/reference/content.json');
writeFileSync(dest, JSON.stringify(out, null, 0) + '\n');
console.log('monsters', out.monsterCount, 'with attacks', out.monsterWithAttacks, '| weapons', weapons.length, 'armors', armors.length);
console.log('wrote', dest);
