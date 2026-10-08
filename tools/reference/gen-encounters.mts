// Runs the shared scenarios (src/test/resources/reference/scenarios.json) through the ORIGINAL TypeScript
// engine and writes the resulting event logs and final states to src/test/resources/reference/encounters.json.
// The Java EncounterParityTest builds the same scenarios and must reproduce this output exactly.
//
//   DND_APP_DIR=/path/to/dnd-app node --experimental-transform-types tools/reference/gen-encounters.mts
//
// Upstream baseline: Zr0AM/dnd-app @ 8db9df32179604057009203c9effa5bc91c9dd6f.
//
// Combatants may be content recipes (martial, caster, filler, monster) resolved through the real seed loaders and
// compilers; build-sweep.json.gz is made entirely of those, so it checks the whole content layer end to end.
//
// The generated sweep (sweep.json.gz) is compared by hash: a SHA-256 of the canonical JSON of each fight's events and
// final states. Use DUMP=<name> to print one fight's events when a hash mismatches.
//
// Plan intent grammar (one string per intent, tried in order every turn; failed actions are ignored):
//   approach:<rangeFt>                 move toward the nearest enemy until within rangeFt (as far as movement allows)
//   retreat:<cells>                    move <cells> straight away from the nearest enemy
//   attack:<weaponIndex>:<max>         attack the nearest enemy up to <max> times with that weapon
//   cast:<spell>:<slot|->:<rule>:<q|-> cast a spell; slot '-' = spell's own level; 'q' = Quickened Spell
//   lay                                Lay on Hands on the weakest wounded ally
//   mark                               Hunter's Mark on the nearest enemy
//   policy                             run the real tactical AI for this turn (sim/src/ai/policy.ts)
// Target rules: nearest | farthest | strongest (highest max HP) | weakest-ally (lowest HP fraction) | nearest-ally.
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { gunzipSync } from 'node:zlib';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const appDir = process.env.DND_APP_DIR ?? '/home/user/dnd-app';
const load = (rel: string) => import(pathToFileURL(join(appDir, 'sim/src', rel)).href);
const { Random } = await load('rng/rng.ts');
const { Grid, cell, distanceFt } = await load('grid/grid.ts');
const { dice } = await load('dice/dice.ts');
const { Combatant } = await load('combat/actor.ts');
const { Encounter } = await load('combat/encounter.ts');
const { tacticalPolicy } = await load('ai/policy.ts');
const contentDbModule = await load('content/load-db.ts');
const { compileMonster, spawnMonster } = await load('content/monster.ts');
const { multiattackFor } = await load('content/multiattack.ts');
const { loadFillers } = await load('content/fillers.ts');
const { compileBuild } = await load('content/character.ts');
const { compileCaster } = await load('content/caster.ts');
const spellsModule = await load('content/spells.ts');
const { DarkOnesBlessingFeature, WildShapeFeature } = await load('content/martial-features.ts');
const { proficiencyBonus } = await load('core/types.ts');

const root = resolve(import.meta.dirname, '../../src/test/resources/reference');
const input = JSON.parse(readFileSync(join(root, 'scenarios.json'), 'utf8'));

const D = (a: number[]) => dice(a[0], a[1], a[2] ?? 0);

function scaling(s: any) {
  switch (s.type) {
    case 'cantrip': {
      return (_slot: number, level: number) =>
        dice(s.count + (level >= 5 ? 1 : 0) + (level >= 11 ? 1 : 0) + (level >= 17 ? 1 : 0), s.sides);
    }
    case 'upcast':
      return (slot: number) => dice(s.count + Math.max(0, slot - s.baseLevel) * s.perUpcast, s.sides);
    case 'fixed':
      return () => dice(s.count, s.sides, s.bonus ?? 0);
    default:
      throw new Error('scaling ' + s.type);
  }
}

function makeSpell(id: string, s: any) {
  const k = s.kind;
  let kind: any;
  switch (k.type) {
    case 'attack-damage':
      kind = {
        type: k.type,
        damage: scaling(k.damage),
        damageType: k.damageType,
        rays: k.rays,
        raysPerUpcast: k.raysPerUpcast,
        beams: k.beamsByLevel
          ? (lvl: number) => k.beamsByLevel.filter((t: number[]) => t[0] <= lvl).at(-1)[1]
          : undefined,
        addSpellMod: k.addSpellMod,
      };
      break;
    case 'save-damage':
      kind = {
        type: k.type,
        save: k.save,
        damage: scaling(k.damage),
        damageType: k.damageType,
        onSuccess: k.onSuccess,
        aoeRadiusFt: k.aoeRadiusFt,
        selfOrigin: k.selfOrigin,
      };
      break;
    case 'heal':
      kind = { type: k.type, dice: scaling(k.dice), addSpellMod: k.addSpellMod };
      break;
    case 'control':
      kind = {
        type: k.type,
        save: k.save,
        condition: k.condition,
        rounds: k.rounds,
        repeatSaveEndsEffect: k.repeatSaveEndsEffect,
        aoeRadiusFt: k.aoeRadiusFt,
      };
      break;
    case 'buff':
      kind = {
        type: k.type,
        buffId: k.buffId,
        maxTargets: k.maxTargets,
        rounds: k.rounds,
        attackBonusDice: k.attackBonusDice ? D(k.attackBonusDice) : undefined,
        saveBonusDice: k.saveBonusDice ? D(k.saveBonusDice) : undefined,
        acBonus: k.acBonus,
        extraAttackAction: k.extraAttackAction,
      };
      break;
    default:
      throw new Error('kind ' + k.type);
  }
  return {
    id,
    name: s.name,
    level: s.level,
    action: s.action,
    rangeFt: s.rangeFt,
    concentration: s.concentration,
    kind,
  };
}
const spells: Record<string, any> = {};
for (const [id, s] of Object.entries(input.spells)) spells[id] = makeSpell(id, s);

// ---- features (each combatant gets its own instance) -------------------------------------------
function makeFeature(id: string): any {
  switch (id) {
    case 'berserk': {
      let riderUsed = false;
      let effectUsed = false;
      return {
        id,
        onTurnStart() {
          riderUsed = false;
          effectUsed = false;
        },
        onHit() {
          if (riderUsed) return [];
          riderUsed = true;
          return [{ damage: dice(1, 6, 0), type: 'necrotic' }];
        },
        onHitEffect() {
          if (effectUsed) return null;
          effectUsed = true;
          return { save: 'con', dc: 11, condition: 'stunned', rounds: 1 };
        },
        onKill(self: any) {
          self.grantTempHp(5);
        },
        resistsDamage(_self: any, type: string) {
          return type === 'bludgeoning' || type === 'piercing' || type === 'slashing';
        },
      };
    }
    case 'reckless':
      return {
        id,
        outgoingAttack(_self: any, _target: any, weapon: any) {
          return weapon.kind === 'melee' ? { advantage: true } : null;
        },
        grantsAttackersAdvantage() {
          return true;
        },
      };
    case 'flurry':
      return {
        id,
        bonusAttackActions() {
          return 1;
        },
      };
    case 'hunters-mark':
      return {
        id,
        onHit(ctx: any) {
          return ctx.self.markedTarget === ctx.target.id ? [{ damage: dice(1, 6, 0), type: 'force' }] : [];
        },
      };
    case 'aura-of-protection':
      return { id };
    default:
      throw new Error('feature ' + id);
  }
}

// ---- building combatants and grids ----------------------------------------------------------------
const ABILITY = ['str', 'dex', 'con', 'int', 'wis', 'cha'];
function makeCombatant(c: any) {
  const spec: any = {
    id: c.id,
    name: c.name,
    side: c.side,
    level: c.level,
    abilities: Object.fromEntries(ABILITY.map((a, i) => [a, c.abilities[i]])),
    ac: c.ac,
    maxHp: c.maxHp,
    position: cell(c.position[0], c.position[1]),
    attacks: c.attacks.map((a: any) => ({
      name: a.name,
      kind: a.kind,
      reachFt: a.reachFt,
      rangeFt: a.rangeFt,
      rangeLongFt: a.rangeLongFt,
      attackBonus: a.attackBonus,
      damage: D(a.damage),
      damageType: a.damageType,
      extraDamage: a.extraDamage?.map((e: any) => ({ damage: D(e.damage), type: e.type })),
      critRange: a.critRange,
      finesse: a.finesse,
    })),
    features: (c.features ?? []).map(makeFeature),
    extraAttacks: c.extraAttacks,
    legendaryActions: c.legendaryActions,
    saveProficiencies: c.saveProficiencies,
    saveBonuses: c.saveBonuses,
    damageResponses: c.damageResponses,
    speedFt: c.speedFt,
    resources: c.resources,
  };
  if (c.spellcasting) {
    spec.spellcasting = {
      ability: c.spellcasting.ability,
      slots: c.spellcasting.slots.map((s: number[]) => ({ level: s[0], count: s[1] })),
      cantrips: c.spellcasting.cantrips.map((id: string) => spells[id]),
      spells: c.spellcasting.spells.map((id: string) => spells[id]),
      shortRestSlots: c.spellcasting.shortRestSlots,
    };
  }
  const combatant = new Combatant(spec);
  if (c.startHp !== undefined) combatant.hp = c.startHp;
  for (const cond of c.conditions ?? []) combatant.addCondition(cond);
  return combatant;
}

// ---- content recipes: combatants built by the real seed loaders and compilers ----------------------
const seedDb = contentDbModule.buildSeedDatabase();
const monsterTemplates = new Map<string, any>();
for (const src of contentDbModule.loadMonsterSources(seedDb)) {
  monsterTemplates.set(src.monster.monsterSlug, compileMonster(src, multiattackFor(src.monster.monsterSlug)));
}
const fillerCache = new Map<number, any>();
const fillersFor = (level: number) => {
  if (!fillerCache.has(level)) fillerCache.set(level, loadFillers(seedDb, level));
  return fillerCache.get(level);
};
const spellById: Record<string, any> = {};
for (const v of Object.values(spellsModule) as any[]) {
  if (v && typeof v === 'object' && !Array.isArray(v) && 'kind' in v && 'id' in v) spellById[v.id] = v;
}
const abilityRecord = (a: number[]) => Object.fromEntries(ABILITY.map((k, i) => [k, a[i]]));
const resolveWeapon = (w: any) =>
  typeof w === 'string'
    ? contentDbModule.loadWeapon(seedDb, w)
    : { name: w.name, category: w.category, range: w.range, diceCount: w.diceCount, diceSides: w.diceSides, damageType: w.damageType, properties: w.properties };

function makeMartial(c: any) {
  const b = c.martial;
  const spec: any = {
    id: c.id,
    name: `${b.class} hero`,
    side: c.side,
    class: contentDbModule.loadClass(seedDb, b.class),
    subclass: b.subclass,
    level: b.level,
    abilities: abilityRecord(b.abilities),
    weapon: resolveWeapon(b.weapon),
    twoHanded: b.twoHanded,
    armor: b.armor ? contentDbModule.loadArmor(seedDb, b.armor) : null,
    shield: b.shield,
    fightingStyle: b.fightingStyle ?? undefined,
    unarmoredDefense: b.unarmoredDefense ?? null,
    progression: contentDbModule.loadProgression(seedDb, b.class, b.level),
    position: cell(c.position[0], c.position[1]),
  };
  if (b.gish) {
    spec.spellcasting = {
      ability: 'cha',
      slots: contentDbModule.loadSpellSlots(seedDb, b.class, b.level),
      cantrips: [],
      spells: [spellById['bless'], spellById['cure-wounds']],
    };
  }
  return compileBuild(spec);
}

function makeCasterBuild(c: any) {
  const b = c.caster;
  const resources = (b.resources ?? []).map((r: any) => ({
    id: r.id,
    max: r.max === 'level' ? b.level : r.max,
    rechargeShort: r.rechargeShort,
    rechargeLong: r.rechargeLong,
  }));
  const features: any[] = (b.features ?? []).map((id: string) => {
    if (id === 'dark-ones-blessing') return new DarkOnesBlessingFeature();
    throw new Error('caster feature ' + id);
  });
  if (b.wildShape) {
    features.push(
      new WildShapeFeature({
        hp: 2 * b.level,
        ac: 13,
        attack: { name: 'Bite', kind: 'melee', reachFt: 5, attackBonus: 2 + proficiencyBonus(b.level), damage: dice(2, 6, 2), damageType: 'piercing' },
      }),
    );
  }
  return compileCaster({
    id: c.id,
    name: `${b.class} hero`,
    side: c.side,
    class: contentDbModule.loadClass(seedDb, b.class),
    subclass: b.subclass,
    level: b.level,
    abilities: abilityRecord(b.abilities),
    weapon: contentDbModule.loadWeapon(seedDb, b.weapon),
    armor: b.armor ? contentDbModule.loadArmor(seedDb, b.armor) : null,
    shield: b.shield,
    spellAbility: b.spellAbility,
    cantrips: b.cantrips.map((id: string) => spellById[id]),
    spells: b.spells.map((id: string) => spellById[id]),
    slots: contentDbModule.loadSpellSlots(seedDb, b.class, b.level),
    position: cell(c.position[0], c.position[1]),
    resources: resources.length ? resources : undefined,
    features: features.length ? features : undefined,
    shortRestSlots: b.shortRestSlots,
    extraHp: b.extraHpPerLevel ? b.extraHpPerLevel * b.level : undefined,
    unarmoredAcAbility: b.unarmoredAcAbility,
  });
}

/** Builds a combatant from a recipe (martial, caster, filler or monster) via the real content layer. */
function makeFromRecipe(c: any) {
  let combatant: any;
  if (c.martial) combatant = makeMartial(c);
  else if (c.caster) combatant = makeCasterBuild(c);
  else if (c.filler) combatant = fillersFor(c.level)[c.filler].make(c.id, c.side, cell(c.position[0], c.position[1]));
  else combatant = spawnMonster(monsterTemplates.get(c.monster), { id: c.id, side: c.side, position: cell(c.position[0], c.position[1]) });
  if (c.startHp !== undefined) combatant.hp = c.startHp;
  for (const cond of c.conditions ?? []) combatant.addCondition(cond);
  return combatant;
}
const isRecipe = (c: any) => c.martial || c.caster || c.filler || c.monster;

function makeGrid(g: any) {
  const grid = new Grid(g.width, g.height, g.cellFt ?? 5);
  for (const [x, y] of g.walls ?? []) grid.setTerrain(cell(x, y), { wall: true });
  for (const [x, y] of g.difficult ?? []) grid.setTerrain(cell(x, y), { difficult: true });
  return grid;
}

// ---- the plan interpreter (mirrored exactly by the Java test) --------------------------------------
const dist = (a: any, b: any) => distanceFt(a.position, b.position);

function minBy<T>(list: T[], key: (t: T) => number): T | undefined {
  let best: T | undefined;
  let bestKey = Infinity;
  for (const t of list) {
    const k = key(t);
    if (k < bestKey) {
      bestKey = k;
      best = t;
    }
  }
  return best;
}

function pick(rule: string, api: any): any {
  const self = api.self;
  switch (rule) {
    case 'nearest':
      return minBy(api.enemies(), (c: any) => dist(self, c));
    case 'farthest':
      return minBy(api.enemies(), (c: any) => -dist(self, c));
    case 'strongest':
      return minBy(api.enemies(), (c: any) => -c.maxHp);
    case 'nearest-ally':
      return minBy(api.allies(), (c: any) => dist(self, c));
    case 'weakest-ally': {
      let best: any;
      for (const c of api.allAllies()) {
        if (c.hp >= c.maxHp) continue;
        if (!best || c.hp * best.maxHp < best.hp * c.maxHp) best = c;
      }
      return best;
    }
    default:
      throw new Error('rule ' + rule);
  }
}

function stepToward(from: any, to: any, steps: number) {
  let { x, y } = from;
  for (let i = 0; i < steps; i++) {
    x += Math.sign(to.x - x);
    y += Math.sign(to.y - y);
  }
  return cell(x, y);
}

function runIntent(intent: string, api: any) {
  const [kind, ...p] = intent.split(':');
  const self = api.self;
  switch (kind) {
    case 'approach': {
      const t = pick('nearest', api);
      if (!t) return;
      const rangeFt = Number(p[0]);
      const d = dist(self, t);
      if (d <= rangeFt) return;
      const need = Math.ceil((d - rangeFt) / 5);
      const can = Math.floor(api.resources.movementFt / 5);
      const steps = Math.min(need, can);
      if (steps <= 0) return;
      api.moveTo(stepToward(self.position, t.position, steps));
      return;
    }
    case 'retreat': {
      const t = pick('nearest', api);
      if (!t) return;
      const n = Number(p[0]);
      const dx = Math.sign(self.position.x - t.position.x);
      const dy = Math.sign(self.position.y - t.position.y);
      api.moveTo(cell(self.position.x + dx * n, self.position.y + dy * n));
      return;
    }
    case 'attack': {
      const weapon = self.attacks[Number(p[0])];
      for (let i = 0; i < Number(p[1]); i++) {
        const t = pick('nearest', api);
        if (!t) break;
        if (api.attack(t, weapon) === null) break;
      }
      return;
    }
    case 'cast': {
      const t = pick(p[2], api);
      if (!t) return;
      const slot = p[1] === '-' ? undefined : Number(p[1]);
      api.castSpell(spells[p[0]], t, slot, p[3] === 'q');
      return;
    }
    case 'lay': {
      const t = pick('weakest-ally', api);
      if (t) api.layOnHands(t);
      return;
    }
    case 'mark': {
      const t = pick('nearest', api);
      if (t) api.markTarget(t);
      return;
    }
    case 'policy':
      tacticalPolicy(api);
      return;
    default:
      throw new Error('intent ' + kind);
  }
}

// ---- run -------------------------------------------------------------------------------------------
function runOne(sc: any) {
  const combatants = sc.combatants.map((c: any) => (isRecipe(c) ? makeFromRecipe(c) : makeCombatant(c)));
  const e = new Encounter({
    grid: makeGrid(sc.grid),
    combatants,
    rng: new Random(sc.seed),
    policyFor: (c: any) => {
      const plan: string[] = sc.plans[c.id] ?? [];
      return (api: any) => {
        for (const intent of plan) runIntent(intent, api);
      };
    },
  });
  const r = e.run(sc.roundCap);
  return {
    name: sc.name,
    rounds: r.rounds,
    winner: r.winner,
    events: r.log,
    final: combatants.map((c: any) => ({
      id: c.id,
      hp: c.hp,
      tempHp: c.tempHp,
      dead: c.dead,
      stable: c.stable,
      x: c.position.x,
      y: c.position.y,
    })),
  };
}

/** JSON with recursively sorted keys and no whitespace; the Java test builds the identical string. */
function canon(v: any): string {
  if (v === null) return 'null';
  if (Array.isArray(v)) return '[' + v.map(canon).join(',') + ']';
  if (typeof v === 'object')
    return '{' + Object.keys(v).sort().map((k) => JSON.stringify(k) + ':' + canon(v[k])).join(',') + '}';
  return JSON.stringify(v);
}
const sha256 = (text: string) => createHash('sha256').update(text).digest('hex');

const sweepInput = JSON.parse(gunzipSync(readFileSync(join(root, 'sweep.json.gz'))).toString('utf8'));

// DUMP=<scenario name> prints that scenario's events as JSON and exits (for debugging a parity mismatch).
if (process.env.DUMP) {
  const buildForDump = JSON.parse(gunzipSync(readFileSync(join(root, 'build-sweep.json.gz'))).toString('utf8'));
  const sc = [...input.scenarios, ...sweepInput.scenarios, ...buildForDump.scenarios].find((s: any) => s.name === process.env.DUMP);
  if (!sc) throw new Error('no scenario named ' + process.env.DUMP);
  const out = runOne(sc);
  console.log(JSON.stringify(out.events, null, 1));
  process.exit(0);
}

const results = input.scenarios.map(runOne);
const sweep = sweepInput.scenarios.map((sc: any) => {
  const r = runOne(sc);
  const text = canon({ events: r.events, final: r.final, rounds: r.rounds, winner: r.winner });
  return { name: sc.name, rounds: r.rounds, winner: r.winner, events: r.events.length, sha256: sha256(text) };
});

const buildInput = JSON.parse(gunzipSync(readFileSync(join(root, 'build-sweep.json.gz'))).toString('utf8'));
const buildSweep = buildInput.scenarios.map((sc: any) => {
  const r = runOne(sc);
  const text = canon({ events: r.events, final: r.final, rounds: r.rounds, winner: r.winner });
  return { name: sc.name, rounds: r.rounds, winner: r.winner, events: r.events.length, sha256: sha256(text) };
});

const dest = join(root, 'encounters.json');
writeFileSync(dest, JSON.stringify({ source: 'Zr0AM/dnd-app@8db9df32179604057009203c9effa5bc91c9dd6f', results }, null, 1) + '\n');
writeFileSync(join(root, 'build-sweep-expected.json'), JSON.stringify({ source: 'Zr0AM/dnd-app@8db9df32179604057009203c9effa5bc91c9dd6f', sweep: buildSweep }) + '\n');
writeFileSync(join(root, 'sweep-expected.json'), JSON.stringify({ source: 'Zr0AM/dnd-app@8db9df32179604057009203c9effa5bc91c9dd6f', sweep }) + '\n');
for (const r of results) {
  const kinds: Record<string, number> = {};
  for (const ev of r.events) kinds[ev.kind] = (kinds[ev.kind] ?? 0) + 1;
  console.log(r.name.padEnd(24), 'rounds', r.rounds, 'winner', r.winner, JSON.stringify(kinds));
}
const total = sweep.reduce((n: number, s: any) => n + s.events, 0);
const wins = sweep.reduce((m: Record<string, number>, s: any) => ((m[String(s.winner)] = (m[String(s.winner)] ?? 0) + 1), m), {});
console.log('sweep:', sweep.length, 'fights,', total, 'events, winners', JSON.stringify(wins));
const buildEvents = buildSweep.reduce((n: number, x: any) => n + x.events, 0);
console.log('build sweep:', buildSweep.length, 'fights,', buildEvents, 'events');
console.log('wrote', dest);
