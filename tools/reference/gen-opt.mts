// Runs the ORIGINAL TypeScript optimizer pieces (genome operators, buildFromGenome through the solo evaluator, NSGA-II,
// reports, campaign, anchor) and writes their results to sim-domain/src/test/resources/reference/opt-expected.json.
// OptimizerParityTest reproduces them in Java and must agree draw for draw.
//
//   DND_APP_DIR=/path/to/dnd-app node --experimental-transform-types --import ./tools/reference/register-ts.mjs \
//     tools/reference/gen-opt.mts
//
// Upstream baseline: Zr0AM/dnd-app @ 8db9df32179604057009203c9effa5bc91c9dd6f.
import { writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const appDir = process.env.DND_APP_DIR ?? '/home/user/dnd-app';
const load = (rel: string) => import(pathToFileURL(join(appDir, 'sim/src', rel)).href);
const { Random } = await load('rng/rng.ts');
const { buildSeedDatabase } = await load('content/load-db.ts');
const { loadMartialCatalog } = await load('opt/catalog.ts');
const genomeMod = await load('opt/genome.ts');
const { evaluate } = await load('opt/evaluate.ts');
const nsga = await load('opt/nsga2.ts');
const reports = await load('opt/reports.ts');
const campaign = await load('opt/campaign.ts');
const anchor = await load('opt/anchor.ts');
const roles = await load('opt/roles.ts');

const root = resolve(import.meta.dirname, '../../sim-domain/src/test/resources/reference');
const db = buildSeedDatabase();
const catalogs = new Map<number, any>();
const catalogFor = (level: number) => {
  if (!catalogs.has(level)) catalogs.set(level, loadMartialCatalog(db, level));
  return catalogs.get(level);
};
const inf = (x: number) => (x === Infinity ? 'inf' : x);
const genomeJson = (g: any) => ({ ...g }); // undefined fields are dropped by JSON.stringify

// ---- genome operators: chained so each step feeds the next ----------------------------------------------------
const cat3 = catalogFor(3);
const opsRandom = new Random(20261010);
const operators: any[] = [];
const subset = ['fighter', 'wizard', 'monk'];
for (let i = 0; i < 90; i++) {
  const classes = i % 3 === 2 ? subset : undefined;
  const g = genomeMod.randomGenome(cat3, opsRandom, `r:${i}`, classes);
  const m = genomeMod.mutate(g, cat3, opsRandom, `m:${i}`, classes);
  const m2 = genomeMod.mutate(m, cat3, opsRandom, `m2:${i}`, classes);
  const g2 = genomeMod.randomGenome(cat3, opsRandom, `r2:${i}`);
  const x = genomeMod.crossover(g, g2, cat3, opsRandom, `x:${i}`);
  operators.push({ classes: classes ?? null, g: genomeJson(g), m: genomeJson(m), m2: genomeJson(m2), g2: genomeJson(g2), x: genomeJson(x), keys: [g, m, x].map(genomeMod.genomeKey) });
}

// ---- every class at every checkpoint, through buildFromGenome and the solo evaluator -----------------------------
const builds: any[] = [];
const buildRandom = new Random(20261011);
for (const level of [3, 5, 11, 17]) {
  const catalog = catalogFor(level);
  for (const cls of genomeMod.BUILD_CLASSES) {
    for (let i = 0; i < 4; i++) {
      const g = genomeMod.randomGenome(catalog, buildRandom, `b:${level}:${cls}:${i}`, [cls]);
      const r = evaluate(g, catalog, { runs: 2 });
      builds.push({ level, genome: genomeJson(g), key: genomeMod.genomeKey(g), result: r });
    }
  }
}

// ---- pure NSGA-II cores on synthetic points (with ties) ----------------------------------------------------------
const sortRandom = new Random(20261012);
const sortCases: any[] = [];
for (let c = 0; c < 6; c++) {
  const rng = sortRandom.stream(`pts:${c}`);
  const n = 12 + c * 7;
  const points = Array.from({ length: n }, () => Array.from({ length: 6 }, () => Math.floor(rng() * (c % 2 === 0 ? 4 : 50))));
  const fronts = nsga.fastNonDominatedSort(points);
  sortCases.push({ points, fronts, crowding: fronts.map((f: number[]) => nsga.crowdingDistances(points, f).map(inf)) });
}

// ---- NSGA-II runs with reports, rescoring and campaign annotation ----------------------------------------------
const individualJson = (ind: any) => ({
  key: genomeMod.genomeKey(ind.genome),
  genome: genomeJson(ind.genome),
  objectives: ind.objectives,
  rank: ind.rank,
  crowding: inf(ind.crowding),
  fitness: ind.result.fitness,
});
const runs: any[] = [];
const runSpecs = [
  { level: 3, seed: 42, populationSize: 12, generations: 4, evalRuns: 2, classes: null as string[] | null },
  { level: 3, seed: 7, populationSize: 10, generations: 3, evalRuns: 2, classes: ['fighter', 'barbarian', 'wizard', 'cleric'] },
  { level: 11, seed: 99, populationSize: 8, generations: 2, evalRuns: 2, classes: null },
];
for (const spec of runSpecs) {
  const catalog = catalogFor(spec.level);
  const result = nsga.runNsga2(catalog, new Random(spec.seed), {
    populationSize: spec.populationSize,
    generations: spec.generations,
    eval: { runs: spec.evalRuns },
    classes: spec.classes ?? undefined,
  });
  const config = { level: spec.level, seed: spec.seed, populationSize: spec.populationSize, generations: spec.generations, evalRuns: spec.evalRuns };
  const report = reports.buildReport(result, config);
  const tank = reports.rescore(report, roles.roleWeights('tank'));
  const healer = reports.rescore(report, roles.roleWeights('healer'));
  const annotated = campaign.annotateCampaignViability(report, catalog, { days: 3 });
  runs.push({
    spec,
    config,
    front: result.front.map(individualJson),
    population: result.population.map(individualJson),
    report,
    rescored: { tank, healer },
    annotated,
  });
  console.log(`nsga2 L${spec.level} seed ${spec.seed}: front ${result.front.length}, population ${result.population.length}`);
}

// ---- campaign and anchor ----------------------------------------------------------------------------------------
const campaignCases: any[] = [];
const campRandom = new Random(20261013);
for (const level of [3, 11]) {
  const catalog = catalogFor(level);
  for (const cls of ['fighter', 'barbarian', 'wizard', 'warlock', 'sorcerer', 'monk', 'paladin', 'druid']) {
    const g = genomeMod.randomGenome(catalog, campRandom, `c:${level}:${cls}`, [cls]);
    campaignCases.push({ level, genome: genomeJson(g), result: campaign.evaluateAdventuringDay(g, catalog, { days: 4 }) });
  }
}
const anchors = [3, 11].map((level) => {
  const catalog = catalogFor(level);
  const a = anchor.computeAnchor(catalog, undefined, { runs: 2 });
  const vectors = [a, [0.5, 40, 0.3, -20, 1, 0], [1, 0, 0, -50, 0, 3]];
  return { level, anchor: a, normalized: vectors.map((v) => anchor.normalizeAgainstAnchor(v, a)), vectors };
});

writeFileSync(
  join(root, 'opt-expected.json'),
  JSON.stringify({ source: 'Zr0AM/dnd-app@8db9df32179604057009203c9effa5bc91c9dd6f', operators, builds, sortCases, runs, campaignCases, anchors }) + '\n',
);
console.log('wrote opt-expected.json:', operators.length, 'operator chains,', builds.length, 'builds,', campaignCases.length, 'campaign cases');
