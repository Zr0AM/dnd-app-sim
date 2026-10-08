#!/usr/bin/env python3
"""Generates the shared reference scenarios consumed by BOTH engines:

    src/test/resources/reference/scenarios.json    hand-written fights (spells, features, 9 scenarios)
    src/test/resources/reference/sweep.json.gz     300 seeded small fights, all driven by the tactical AI

Run from anywhere:  python3 tools/reference/gen-scenarios.py
Then regenerate the TypeScript expectations (see docs/porting.md). The output is deterministic: the sweep uses a
fixed seed and the gzip header carries no timestamp, so re-running without changes leaves the files byte-identical.
"""
import gzip
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[2]
REF = ROOT / "src/test/resources/reference"
import json

def atk(name, kind, bonus, dmg, dtype, **kw):
    a = {"name": name, "kind": kind, "attackBonus": bonus, "damage": dmg, "damageType": dtype}
    a.update(kw)
    return a

def cb(id, side, level, abil, ac, hp, pos, attacks=None, plan=None, **kw):
    c = {"id": id, "name": id, "side": side, "level": level, "abilities": abil, "ac": ac,
         "maxHp": hp, "position": pos, "attacks": attacks or []}
    c.update(kw)
    return c, (plan or [])

def scenario(name, seed, grid, members, cap=10):
    return {"name": name, "seed": seed, "roundCap": cap, "grid": grid,
            "combatants": [m[0] for m in members],
            "plans": {m[0]["id"]: m[1] for m in members if m[1]}}

cant = lambda c, s: {"type": "cantrip", "count": c, "sides": s}
up = lambda base, c, s, per=1: {"type": "upcast", "baseLevel": base, "count": c, "sides": s, "perUpcast": per}
fixed = lambda c, s, b=0: {"type": "fixed", "count": c, "sides": s, "bonus": b}

spells = {
  "fire-bolt": {"name": "Fire Bolt", "level": 0, "action": "action", "rangeFt": 120, "concentration": False,
                "kind": {"type": "attack-damage", "damage": cant(1, 10), "damageType": "fire"}},
  "eldritch-blast": {"name": "Eldritch Blast", "level": 0, "action": "action", "rangeFt": 120, "concentration": False,
                "kind": {"type": "attack-damage", "damage": fixed(1, 10), "damageType": "force",
                         "beamsByLevel": [[1, 1], [5, 2], [11, 3], [17, 4]], "addSpellMod": True}},
  "sacred-flame": {"name": "Sacred Flame", "level": 0, "action": "action", "rangeFt": 60, "concentration": False,
                "kind": {"type": "save-damage", "save": "dex", "damage": cant(1, 8), "damageType": "radiant", "onSuccess": "none"}},
  "scorching-ray": {"name": "Scorching Ray", "level": 2, "action": "action", "rangeFt": 120, "concentration": False,
                "kind": {"type": "attack-damage", "damage": fixed(2, 6), "damageType": "fire", "rays": 3, "raysPerUpcast": 1}},
  "burning-hands": {"name": "Burning Hands", "level": 1, "action": "action", "rangeFt": 15, "concentration": False,
                "kind": {"type": "save-damage", "save": "dex", "damage": up(1, 3, 6), "damageType": "fire", "onSuccess": "half",
                         "aoeRadiusFt": 15, "selfOrigin": True}},
  "fireball": {"name": "Fireball", "level": 3, "action": "action", "rangeFt": 150, "concentration": False,
                "kind": {"type": "save-damage", "save": "dex", "damage": up(3, 8, 6), "damageType": "fire", "onSuccess": "half",
                         "aoeRadiusFt": 20}},
  "hold-person": {"name": "Hold Person", "level": 2, "action": "action", "rangeFt": 60, "concentration": True,
                "kind": {"type": "control", "save": "wis", "condition": "paralyzed", "rounds": 10, "repeatSaveEndsEffect": True}},
  "hypnotic-pattern": {"name": "Hypnotic Pattern", "level": 3, "action": "action", "rangeFt": 120, "concentration": True,
                "kind": {"type": "control", "save": "wis", "condition": "incapacitated", "rounds": 10,
                         "repeatSaveEndsEffect": False, "aoeRadiusFt": 15}},
  "cure-wounds": {"name": "Cure Wounds", "level": 1, "action": "action", "rangeFt": 5, "concentration": False,
                "kind": {"type": "heal", "dice": up(1, 2, 8), "addSpellMod": True}},
  "healing-word": {"name": "Healing Word", "level": 1, "action": "bonus", "rangeFt": 60, "concentration": False,
                "kind": {"type": "heal", "dice": up(1, 1, 4), "addSpellMod": True}},
  "bless": {"name": "Bless", "level": 1, "action": "action", "rangeFt": 30, "concentration": True,
                "kind": {"type": "buff", "buffId": "bless", "maxTargets": 3, "rounds": 10,
                         "attackBonusDice": [1, 4, 0], "saveBonusDice": [1, 4, 0]}},
  "shield-of-faith": {"name": "Shield of Faith", "level": 1, "action": "action", "rangeFt": 60, "concentration": True,
                "kind": {"type": "buff", "buffId": "shield-of-faith", "maxTargets": 1, "rounds": 10, "acBonus": 2}},
  "haste": {"name": "Haste", "level": 3, "action": "action", "rangeFt": 30, "concentration": True,
                "kind": {"type": "buff", "buffId": "haste", "maxTargets": 1, "rounds": 10, "acBonus": 2, "extraAttackAction": True}},
}

open_grid = lambda w, h: {"width": w, "height": h}

# --- A: melee brawl with features, resistances, extra attacks, crits ----------------------------------
brawl = scenario("melee-brawl", 101, open_grid(16, 12), [
  cb("ftr1", "party", 5, [18, 14, 16, 10, 10, 10], 18, 52, [1, 5],
     [atk("greatsword", "melee", 7, [2, 6, 4], "slashing", reachFt=5, critRange=19)],
     ["approach:5", "attack:0:2"], features=["berserk", "reckless"], extraAttacks=1,
     saveProficiencies=["str", "con"]),
  cb("ftr2", "party", 5, [16, 14, 14, 10, 12, 8], 17, 44, [1, 7],
     [atk("longsword", "melee", 6, [1, 8, 3], "slashing", reachFt=5),
      atk("shortbow", "ranged", 5, [1, 6, 2], "piercing", rangeFt=80, rangeLongFt=320)],
     ["approach:5", "attack:0:3"], features=["flurry"], extraAttacks=1),
  cb("g1", "enemy", 1, [8, 14, 10, 10, 8, 8], 15, 13, [11, 4],
     [atk("scimitar", "melee", 4, [1, 6, 2], "slashing", reachFt=5)], ["approach:5", "attack:0:1"]),
  cb("g2", "enemy", 1, [8, 14, 10, 10, 8, 8], 15, 13, [11, 6],
     [atk("scimitar", "melee", 4, [1, 6, 2], "slashing", reachFt=5)], ["approach:5", "attack:0:1"]),
  cb("g3", "enemy", 1, [8, 14, 10, 10, 8, 8], 15, 13, [11, 8],
     [atk("scimitar", "melee", 4, [1, 6, 2], "slashing", reachFt=5)], ["approach:5", "attack:0:1"]),
  cb("brute", "enemy", 3, [18, 10, 16, 6, 8, 6], 13, 59, [12, 6],
     [atk("flaming-axe", "melee", 6, [1, 12, 4], "slashing", reachFt=5,
          extraDamage=[{"damage": [1, 6, 0], "type": "fire"}])],
     ["approach:5", "attack:0:2"], extraAttacks=1,
     damageResponses={"slashing": "resistant", "fire": "vulnerable"}),
])

# --- B: casters, control, buffs, healing, concentration, aura ------------------------------------------
orc = lambda id, pos: cb(id, "enemy", 2, [16, 12, 16, 7, 11, 10], 13, 30, pos,
     [atk("greataxe", "melee", 5, [1, 12, 3], "slashing", reachFt=5)], ["approach:5", "attack:0:1"])
casters = scenario("casters-and-support", 202, open_grid(20, 14), [
  cb("wiz", "party", 5, [8, 14, 14, 18, 12, 10], 13, 32, [1, 6], [],
     ["cast:hypnotic-pattern:3:nearest:-", "cast:hold-person:2:strongest:-", "cast:fireball:3:nearest:-",
      "cast:scorching-ray:2:nearest:-", "cast:burning-hands:1:nearest:-", "cast:fire-bolt:-:nearest:-"],
     spellcasting={"ability": "int", "slots": [[1, 4], [2, 3], [3, 2]], "cantrips": ["fire-bolt"],
                   "spells": ["burning-hands", "scorching-ray", "fireball", "hold-person", "hypnotic-pattern"]}),
  cb("clr", "party", 5, [13, 10, 14, 8, 16, 12], 18, 38, [2, 5],
     [atk("mace", "melee", 4, [1, 6, 1], "bludgeoning", reachFt=5)],
     ["cast:bless:1:nearest-ally:-", "cast:healing-word:1:weakest-ally:-", "cast:cure-wounds:1:weakest-ally:-",
      "cast:sacred-flame:-:nearest:-"],
     saveProficiencies=["wis", "cha"],
     spellcasting={"ability": "wis", "slots": [[1, 4], [2, 3]], "cantrips": ["sacred-flame"],
                   "spells": ["cure-wounds", "healing-word", "bless"]}),
  cb("tnk", "party", 5, [16, 10, 16, 8, 12, 16], 19, 50, [2, 7],
     [atk("longsword", "melee", 6, [1, 8, 3], "slashing", reachFt=5)], ["approach:5", "attack:0:3"],
     features=["aura-of-protection"], extraAttacks=1, saveProficiencies=["wis", "cha"]),
  cb("bard", "party", 5, [8, 14, 13, 10, 12, 16], 15, 30, [1, 8],
     [atk("rapier", "melee", 5, [1, 8, 2], "piercing", reachFt=5, finesse=True)],
     ["cast:haste:3:nearest-ally:-", "approach:5", "attack:0:2"],
     spellcasting={"ability": "cha", "slots": [[1, 4], [2, 3], [3, 2]], "cantrips": [], "spells": ["haste", "bless"]}),
  orc("o1", [10, 4]), orc("o2", [10, 6]), orc("o3", [10, 8]), orc("o4", [11, 5]), orc("o5", [11, 7]),
  cb("ogre", "enemy", 4, [19, 8, 16, 5, 7, 7], 11, 59, [3, 6],
     [atk("great-club", "melee", 9, [3, 10, 6], "bludgeoning", reachFt=5)], ["approach:5", "attack:0:1"]),
  cb("archer", "enemy", 2, [10, 16, 12, 10, 10, 10], 14, 24, [14, 6],
     [atk("shortbow", "ranged", 5, [1, 6, 3], "piercing", rangeFt=80, rangeLongFt=320)], ["attack:0:1"]),
], cap=12)

# --- C: legendary boss, death saves, Lay on Hands, Eldritch Blast + onKill -------------------------------
boss = scenario("legendary-boss", 303, open_grid(14, 12), [
  cb("h1", "party", 5, [10, 16, 12, 10, 10, 10], 15, 40, [4, 5],
     [atk("shortsword", "melee", 6, [1, 6, 3], "piercing", reachFt=5, finesse=True)], ["approach:5", "attack:0:2"],
     startHp=0),
  cb("h2", "party", 5, [8, 14, 14, 10, 12, 18], 13, 36, [3, 7], [],
     ["cast:eldritch-blast:-:nearest:-"], features=["berserk"],
     spellcasting={"ability": "cha", "slots": [], "cantrips": ["eldritch-blast"], "spells": []}),
  cb("h3", "party", 5, [16, 10, 14, 8, 10, 16], 18, 46, [3, 3],
     [atk("longsword", "melee", 6, [1, 8, 3], "slashing", reachFt=5)],
     ["lay", "approach:5", "attack:0:2"], features=["aura-of-protection"], extraAttacks=1,
     resources=[{"id": "lay-on-hands", "max": 25}]),
  cb("boss", "enemy", 1, [20, 10, 18, 10, 12, 14], 17, 180, [9, 5],
     [atk("claw", "melee", 9, [1, 4, 1], "slashing", reachFt=10),
      atk("tail", "melee", 9, [3, 10, 5], "bludgeoning", reachFt=10)], ["approach:10", "attack:0:2"],
     legendaryActions=3, extraAttacks=1),
  cb("add1", "enemy", 1, [10, 12, 10, 6, 8, 6], 13, 20, [10, 3],
     [atk("rusty-sword", "melee", 3, [1, 6, 1], "slashing", reachFt=5)], ["approach:5", "attack:0:1"]),
  cb("add2", "enemy", 1, [10, 12, 10, 6, 8, 6], 12, 6, [10, 8],
     [atk("rusty-sword", "melee", 3, [1, 6, 1], "slashing", reachFt=5)], ["approach:5", "attack:0:1"]),
])

# --- D: terrain, retreat (opportunity attacks), Hunter's Mark, Quickened Spell -------------------------
terrain = {"width": 14, "height": 10,
           "walls": [[7, y] for y in range(10) if y != 5],
           "difficult": [[4, 4], [4, 5], [4, 6], [5, 5]]}
skirmish = scenario("terrain-and-reactions", 404, terrain, [
  cb("arch", "party", 5, [10, 18, 12, 10, 12, 10], 15, 34, [3, 5],
     [atk("longbow", "ranged", 7, [1, 8, 4], "piercing", rangeFt=150, rangeLongFt=600)],
     ["retreat:2", "attack:0:2"], extraAttacks=1),
  cb("rgr", "party", 5, [12, 16, 14, 10, 14, 8], 16, 40, [2, 3],
     [atk("longbow", "ranged", 6, [1, 8, 3], "piercing", rangeFt=150, rangeLongFt=600)],
     ["mark", "attack:0:2"], resources=[{"id": "hunters-mark", "max": 2}], extraAttacks=1),
  cb("sorc", "party", 5, [8, 14, 14, 10, 12, 18], 13, 30, [2, 7], [],
     ["cast:scorching-ray:2:nearest:q", "cast:fire-bolt:-:nearest:-"],
     resources=[{"id": "sorcery", "max": 6}],
     spellcasting={"ability": "cha", "slots": [[2, 3]], "cantrips": ["fire-bolt"], "spells": ["scorching-ray"]}),
  cb("m1", "enemy", 2, [16, 12, 14, 8, 10, 8], 14, 28, [6, 5],
     [atk("axe", "melee", 5, [1, 8, 3], "slashing", reachFt=5)], ["approach:5", "attack:0:1"]),
  cb("m2", "enemy", 2, [16, 12, 14, 8, 10, 8], 14, 28, [4, 6],
     [atk("axe", "melee", 5, [1, 8, 3], "slashing", reachFt=5)], ["approach:5", "attack:0:1"]),
  cb("m3", "enemy", 2, [16, 12, 14, 8, 10, 8], 14, 28, [9, 5],
     [atk("axe", "melee", 5, [1, 8, 3], "slashing", reachFt=5)], ["approach:5", "attack:0:1"]),
  cb("guard", "enemy", 2, [16, 12, 14, 8, 10, 8], 14, 28, [4, 4],
     [atk("axe", "melee", 5, [1, 8, 3], "slashing", reachFt=5)], ["attack:0:1"], conditions=["paralyzed"]),
], cap=10)

giant = lambda id, pos: cb(id, "enemy", 6, [23, 8, 21, 9, 10, 12], 16, 400, pos,
     [atk("maul", "melee", 12, [4, 10, 8], "bludgeoning", reachFt=5)], ["attack:0:1"])
pressure = scenario("concentration-pressure", 505, open_grid(12, 10), [
  cb("wiz", "party", 5, [8, 14, 10, 18, 12, 10], 5, 1000, [5, 5], [],
     ["cast:bless:1:nearest-ally:-"],
     spellcasting={"ability": "int", "slots": [[1, 40]], "cantrips": [], "spells": ["bless"]}),
  cb("ally", "party", 5, [16, 12, 14, 10, 10, 10], 16, 1000, [5, 8],
     [atk("sword", "melee", 6, [1, 8, 3], "slashing", reachFt=5)], ["approach:5", "attack:0:2"], extraAttacks=1),
  giant("gi1", [4, 5]), giant("gi2", [6, 5]), giant("gi3", [5, 4]),
], cap=8)

# ===== AI scenarios: every combatant, both sides, is driven by the real tactical policy ================
POL = ["policy"]
def ai(id, side, level, abil, ac, hp, pos, attacks=None, **kw):
    return cb(id, side, level, abil, ac, hp, pos, attacks, POL, **kw)

gob_melee = lambda id, pos: ai(id, "enemy", 1, [8, 14, 10, 10, 8, 8], 15, 13, pos,
     [atk("scimitar", "melee", 4, [1, 6, 2], "slashing", reachFt=5)])
gob_arch = lambda id, pos: ai(id, "enemy", 1, [8, 14, 10, 10, 8, 8], 14, 11, pos,
     [atk("shortbow", "ranged", 4, [1, 6, 2], "piercing", rangeFt=80, rangeLongFt=320)])

ai_skirmish = scenario("ai-martial-skirmish", 606, open_grid(20, 14), [
  ai("ftr", "party", 5, [18, 14, 16, 10, 10, 10], 18, 52, [1, 6],
     [atk("greatsword", "melee", 7, [2, 6, 4], "slashing", reachFt=5, critRange=19)],
     features=["berserk", "reckless"], extraAttacks=1, saveProficiencies=["str", "con"]),
  ai("rgr", "party", 5, [12, 16, 14, 10, 14, 8], 16, 40, [1, 8],
     [atk("longbow", "ranged", 7, [1, 8, 3], "piercing", rangeFt=150, rangeLongFt=600),
      atk("shortsword", "melee", 5, [1, 6, 3], "piercing", reachFt=5)],
     features=["hunters-mark"], resources=[{"id": "hunters-mark", "max": 3}], extraAttacks=1),
  ai("rog", "party", 5, [10, 18, 12, 12, 12, 10], 15, 34, [2, 5],
     [atk("rapier", "melee", 7, [1, 8, 4], "piercing", reachFt=5, finesse=True),
      atk("shortbow", "ranged", 7, [1, 6, 4], "piercing", rangeFt=80, rangeLongFt=320)]),
  gob_melee("g1", [14, 4]), gob_melee("g2", [14, 6]), gob_melee("g3", [14, 8]),
  gob_arch("a1", [17, 5]), gob_arch("a2", [17, 9]),
  ai("brute", "enemy", 3, [18, 10, 16, 6, 8, 6], 13, 59, [15, 7],
     [atk("flaming-axe", "melee", 6, [1, 12, 4], "slashing", reachFt=5,
          extraDamage=[{"damage": [1, 6, 0], "type": "fire"}])],
     extraAttacks=1, damageResponses={"slashing": "resistant", "fire": "vulnerable"}),
], cap=10)

ai_casters = scenario("ai-spellcasters", 707, open_grid(22, 14), [
  ai("wiz", "party", 7, [8, 14, 14, 18, 12, 10], 13, 40, [1, 6], [],
     spellcasting={"ability": "int", "slots": [[1, 4], [2, 3], [3, 3], [4, 1]], "cantrips": ["fire-bolt"],
                   "spells": ["burning-hands", "scorching-ray", "fireball", "hold-person", "hypnotic-pattern"]}),
  ai("clr", "party", 7, [13, 10, 14, 8, 16, 12], 18, 52, [2, 5],
     [atk("mace", "melee", 5, [1, 6, 2], "bludgeoning", reachFt=5)], saveProficiencies=["wis", "cha"],
     spellcasting={"ability": "wis", "slots": [[1, 4], [2, 3], [3, 3], [4, 1]], "cantrips": ["sacred-flame"],
                   "spells": ["cure-wounds", "healing-word", "bless"]}),
  ai("bard", "party", 7, [8, 14, 13, 10, 12, 16], 15, 44, [1, 8],
     [atk("rapier", "melee", 6, [1, 8, 2], "piercing", reachFt=5, finesse=True)],
     spellcasting={"ability": "cha", "slots": [[1, 4], [2, 3], [3, 3], [4, 1]], "cantrips": [], "spells": ["haste", "bless"]}),
  ai("sorc", "party", 7, [8, 14, 14, 10, 12, 18], 13, 38, [2, 7], [],
     resources=[{"id": "sorcery", "max": 7}],
     spellcasting={"ability": "cha", "slots": [[1, 4], [2, 3], [3, 3], [4, 1]], "cantrips": ["fire-bolt"],
                   "spells": ["burning-hands", "scorching-ray", "hold-person"]}),
  ai("tnk", "party", 7, [16, 10, 16, 8, 12, 14], 19, 70, [3, 6],
     [atk("longsword", "melee", 8, [1, 8, 4], "slashing", reachFt=5)], extraAttacks=1,
     features=["aura-of-protection"]),
  orc("o1", [12, 4]), orc("o2", [12, 6]), orc("o3", [12, 8]), orc("o4", [13, 5]), orc("o5", [13, 7]),
  ai("ogre", "enemy", 4, [19, 8, 16, 5, 7, 7], 11, 59, [14, 6],
     [atk("great-club", "melee", 6, [2, 8, 4], "bludgeoning", reachFt=5)]),
  ai("mage", "enemy", 5, [8, 14, 12, 16, 12, 10], 12, 30, [18, 6],
     [atk("dagger", "melee", 4, [1, 4, 2], "piercing", reachFt=5)],
     spellcasting={"ability": "int", "slots": [[1, 4], [2, 3], [3, 2]], "cantrips": ["fire-bolt"],
                   "spells": ["burning-hands", "hold-person", "fireball"]}),
  ai("acolyte", "enemy", 4, [10, 10, 12, 10, 16, 10], 14, 28, [18, 8],
     [atk("club", "melee", 2, [1, 4, 0], "bludgeoning", reachFt=5)],
     spellcasting={"ability": "wis", "slots": [[1, 4], [2, 2]], "cantrips": ["sacred-flame"],
                   "spells": ["cure-wounds", "healing-word", "bless"]}),
], cap=10)

ai_boss = scenario("ai-boss-and-healing", 808, open_grid(16, 12), [
  ai("pal", "party", 7, [16, 10, 14, 8, 10, 16], 19, 64, [3, 6],
     [atk("longsword", "melee", 8, [1, 8, 4], "slashing", reachFt=5)], extraAttacks=1,
     features=["aura-of-protection"], resources=[{"id": "lay-on-hands", "max": 35}],
     spellcasting={"ability": "cha", "slots": [[1, 4], [2, 2]], "cantrips": [], "spells": ["bless", "cure-wounds"]}),
  ai("lock", "party", 7, [8, 14, 14, 10, 12, 18], 13, 46, [2, 8], [],
     features=["berserk"],
     spellcasting={"ability": "cha", "slots": [], "cantrips": ["eldritch-blast"], "spells": []}),
  ai("clr", "party", 7, [13, 10, 14, 8, 16, 12], 18, 52, [2, 4],
     [atk("mace", "melee", 5, [1, 6, 2], "bludgeoning", reachFt=5)], startHp=0,
     spellcasting={"ability": "wis", "slots": [[1, 4], [2, 3], [3, 2]], "cantrips": ["sacred-flame"],
                   "spells": ["cure-wounds", "healing-word"]}),
  ai("rog", "party", 7, [10, 18, 12, 12, 12, 10], 16, 50, [3, 4],
     [atk("rapier", "melee", 8, [1, 8, 4], "piercing", reachFt=5, finesse=True)], startHp=9),
  ai("boss", "enemy", 1, [20, 10, 18, 10, 12, 14], 17, 190, [11, 6],
     [atk("claw", "melee", 9, [1, 4, 1], "slashing", reachFt=10),
      atk("tail", "melee", 9, [3, 10, 5], "bludgeoning", reachFt=10)], legendaryActions=3, extraAttacks=1),
  ai("add1", "enemy", 1, [10, 12, 10, 6, 8, 6], 13, 20, [10, 3],
     [atk("rusty-sword", "melee", 3, [1, 6, 1], "slashing", reachFt=5)]),
  ai("add2", "enemy", 1, [10, 12, 10, 6, 8, 6], 12, 8, [10, 9],
     [atk("rusty-sword", "melee", 3, [1, 6, 1], "slashing", reachFt=5)]),
], cap=10)

ai_crowd = scenario("ai-crowd-control", 909, open_grid(20, 12), [
  ai("wiz", "party", 5, [8, 14, 14, 18, 12, 10], 13, 32, [1, 6], [],
     spellcasting={"ability": "int", "slots": [[1, 4], [2, 3], [3, 2]], "cantrips": ["fire-bolt"],
                   "spells": ["burning-hands", "fireball", "hypnotic-pattern"]}),
  ai("f1", "party", 5, [16, 12, 16, 10, 10, 10], 18, 50, [2, 4],
     [atk("longsword", "melee", 6, [1, 8, 3], "slashing", reachFt=5)], extraAttacks=1),
  ai("f2", "party", 5, [16, 12, 16, 10, 10, 10], 18, 50, [2, 6],
     [atk("longsword", "melee", 6, [1, 8, 3], "slashing", reachFt=5)], extraAttacks=1),
  ai("f3", "party", 5, [16, 12, 16, 10, 10, 10], 18, 50, [2, 8],
     [atk("longsword", "melee", 6, [1, 8, 3], "slashing", reachFt=5)], extraAttacks=1),
] + [gob_melee("w%d" % i, [10 + (i % 3), 2 + i]) for i in range(8)] + [gob_arch("x1", [16, 4]), gob_arch("x2", [16, 8])], cap=10)


# ===== Generated sweep: many small seeded fights so the AI hits many decision boundaries ===============
import random
rnd = random.Random(20261008)

SLOTS = {3: [[1, 4], [2, 2]], 5: [[1, 4], [2, 3], [3, 2]], 7: [[1, 4], [2, 3], [3, 3], [4, 1]],
         9: [[1, 4], [2, 3], [3, 3], [4, 3], [5, 1]], 11: [[1, 4], [2, 3], [3, 3], [4, 3], [5, 2], [6, 1]]}
HALF = {3: [[1, 3]], 5: [[1, 4], [2, 2]], 7: [[1, 4], [2, 3]], 9: [[1, 4], [2, 3], [3, 2]], 11: [[1, 4], [2, 3], [3, 3]]}
EXTRA = lambda L: 0 if L < 5 else (1 if L < 11 else 2)
PB = lambda L: 2 + (L - 1) // 4

def thin(slots):
    out = []
    for lvl, cnt in slots:
        c = rnd.randint(0, cnt)
        if c: out.append([lvl, c])
    return out

def party_member(kind, L, i, pos):
    id = "p%d" % i
    pb = PB(L)
    hp_scale = {"fighter": 10, "archer": 8, "paladin": 9, "ranger": 8, "wizard": 5, "cleric": 7, "bard": 6,
                "sorcerer": 5, "warlock": 6, "gish": 8}[kind]
    hp = hp_scale * L + rnd.randint(0, 10)
    kw = {}
    if kind == "fighter":
        c = cb(id, "party", L, [17, 12, 15, 10, 10, 10], 16 + rnd.randint(0, 3), hp, pos,
               [atk("sword", "melee", pb + 4, [1, rnd.choice([8, 10]), 3], "slashing", reachFt=5)], POL,
               extraAttacks=EXTRA(L), features=rnd.choice([[], ["berserk"], ["reckless"], ["flurry"]]))
    elif kind == "archer":
        c = cb(id, "party", L, [10, 17, 13, 10, 12, 10], 14 + rnd.randint(0, 2), hp, pos,
               [atk("bow", "ranged", pb + 3, [1, 8, 3], "piercing", rangeFt=150, rangeLongFt=600),
                atk("short", "melee", pb + 3, [1, 6, 3], "piercing", reachFt=5)], POL, extraAttacks=EXTRA(L))
    elif kind == "ranger":
        c = cb(id, "party", L, [12, 16, 14, 10, 14, 8], 15, hp, pos,
               [atk("bow", "ranged", pb + 3, [1, 8, 3], "piercing", rangeFt=150, rangeLongFt=600)], POL,
               extraAttacks=EXTRA(L), features=["hunters-mark"], resources=[{"id": "hunters-mark", "max": rnd.randint(0, 3)}])
    elif kind == "paladin":
        c = cb(id, "party", L, [16, 10, 14, 8, 10, 16], 18, hp, pos,
               [atk("sword", "melee", pb + 3, [1, 8, 3], "slashing", reachFt=5)], POL, extraAttacks=EXTRA(L),
               features=["aura-of-protection"], resources=[{"id": "lay-on-hands", "max": 5 * L}],
               spellcasting={"ability": "cha", "slots": thin(HALF[L]), "cantrips": [], "spells": ["bless", "cure-wounds"]})
    elif kind == "wizard":
        pool = ["burning-hands", "scorching-ray", "fireball", "hold-person", "hypnotic-pattern"]
        c = cb(id, "party", L, [8, 14, 14, 18, 12, 10], 12 + rnd.randint(0, 2), hp, pos, [], POL,
               spellcasting={"ability": "int", "slots": thin(SLOTS[L]), "cantrips": ["fire-bolt"],
                             "spells": rnd.sample(pool, rnd.randint(2, 5))})
    elif kind == "cleric":
        c = cb(id, "party", L, [13, 10, 14, 8, 16, 12], 17, hp, pos,
               [atk("mace", "melee", pb + 3, [1, 6, 2], "bludgeoning", reachFt=5)], POL,
               spellcasting={"ability": "wis", "slots": thin(SLOTS[L]), "cantrips": ["sacred-flame"],
                             "spells": rnd.sample(["cure-wounds", "healing-word", "bless", "shield-of-faith"], rnd.randint(1, 4))})
    elif kind == "bard":
        c = cb(id, "party", L, [8, 14, 13, 10, 12, 16], 15, hp, pos,
               [atk("rapier", "melee", pb + 3, [1, 8, 2], "piercing", reachFt=5, finesse=True)], POL,
               spellcasting={"ability": "cha", "slots": thin(SLOTS[L]), "cantrips": [],
                             "spells": rnd.sample(["haste", "bless", "healing-word", "shield-of-faith"], rnd.randint(1, 4))})
    elif kind == "sorcerer":
        pool = ["burning-hands", "scorching-ray", "hold-person", "fireball"]
        c = cb(id, "party", L, [8, 14, 14, 10, 12, 18], 13, hp, pos, [], POL,
               resources=[{"id": "sorcery", "max": rnd.randint(0, L)}],
               spellcasting={"ability": "cha", "slots": thin(SLOTS[L]), "cantrips": [rnd.choice(["fire-bolt", "sacred-flame", "eldritch-blast"])],
                             "spells": rnd.sample(pool, rnd.randint(1, 4))})
    elif kind == "gish":
        c = cb(id, "party", L, [16, 12, 14, 14, 10, 8], 17, hp, pos,
               [atk("sword", "melee", pb + 3, [1, 8, 3], "slashing", reachFt=5)], POL, extraAttacks=max(1, EXTRA(L)),
               spellcasting={"ability": "int", "slots": thin(HALF[L]), "cantrips": [rnd.choice(["fire-bolt", "sacred-flame"])],
                             "spells": rnd.sample(["burning-hands", "scorching-ray", "hold-person"], rnd.randint(1, 3))})
    else:  # warlock
        c = cb(id, "party", L, [8, 14, 14, 10, 12, 18], 13, hp, pos, [], POL, features=rnd.choice([[], ["berserk"]]),
               spellcasting={"ability": "cha", "slots": [], "cantrips": ["eldritch-blast"], "spells": []})
    if rnd.random() < 0.35:
        c[0]["startHp"] = rnd.choice([0, 1, hp // 5, hp // 3, hp // 2])
    return c

def enemy_member(kind, L, i, pos):
    id = "e%d" % i
    if kind == "goblin":
        c = cb(id, "enemy", 1, [8, 14, 10, 10, 8, 8], 15, rnd.randint(7, 15), pos,
               [atk("scimitar", "melee", 4, [1, 6, 2], "slashing", reachFt=5)], POL)
    elif kind == "gobarcher":
        c = cb(id, "enemy", 1, [8, 14, 10, 10, 8, 8], 13, rnd.randint(7, 13), pos,
               [atk("bow", "ranged", 4, [1, 6, 2], "piercing", rangeFt=80, rangeLongFt=320)], POL)
    elif kind == "orc":
        c = cb(id, "enemy", 2, [16, 12, 16, 7, 11, 10], 13, 15 + 3 * L, pos,
               [atk("greataxe", "melee", 5, [1, 12, 3], "slashing", reachFt=5)], POL)
    elif kind == "ogre":
        c = cb(id, "enemy", 4, [19, 8, 16, 5, 7, 7], 11, 40 + 5 * L, pos,
               [atk("club", "melee", 6, [2, 8, 4], "bludgeoning", reachFt=5)], POL)
    elif kind == "brute":
        c = cb(id, "enemy", 3, [18, 10, 16, 6, 8, 6], 13, 30 + 6 * L, pos,
               [atk("axe", "melee", 6, [1, 12, 4], "slashing", reachFt=5, extraDamage=[{"damage": [1, 6, 0], "type": "fire"}])],
               POL, extraAttacks=1, damageResponses={"slashing": "resistant", "fire": "vulnerable"})
    elif kind == "mage":
        c = cb(id, "enemy", min(L, 9), [8, 14, 12, 16, 12, 10], 12, 20 + 3 * L, pos,
               [atk("dagger", "melee", 4, [1, 4, 2], "piercing", reachFt=5)], POL,
               spellcasting={"ability": "int", "slots": thin(SLOTS[3 if L < 5 else 5]), "cantrips": ["fire-bolt"],
                             "spells": rnd.sample(["burning-hands", "hold-person", "scorching-ray"], 2)})
    else:  # boss
        c = cb(id, "enemy", 1, [20, 10, 18, 10, 12, 14], 17, 120 + 15 * L, pos,
               [atk("claw", "melee", 9, [1, 4, 1], "slashing", reachFt=10), atk("tail", "melee", 9, [2, 10, 5], "bludgeoning", reachFt=10)],
               POL, legendaryActions=3, extraAttacks=1)
    if rnd.random() < 0.25:
        c[0]["startHp"] = rnd.choice([1, c[0]["maxHp"] // 4, c[0]["maxHp"] // 2])
    return c

PARTY_KINDS = ["fighter", "archer", "ranger", "paladin", "wizard", "cleric", "bard", "sorcerer", "warlock", "gish"]
ENEMY_KINDS = ["goblin", "goblin", "gobarcher", "orc", "orc", "ogre", "brute", "mage", "boss"]
sweep = []
for n in range(300):
    L = rnd.choice([3, 5, 7, 9, 11])
    W = rnd.choice([16, 16, 24, 32])
    cells = [(x, y) for x in range(1, W - 1) for y in range(1, 9)]
    rnd.shuffle(cells)
    members = []
    k = rnd.randint(1, 4)
    for i in range(k):
        members.append(party_member(rnd.choice(PARTY_KINDS), L, i, list(rnd.choice([c for c in cells if c[0] <= 5 and list(c) not in [m[0]["position"] for m in members]]))))
    for i in range(rnd.randint(1, 5)):
        members.append(enemy_member(rnd.choice(ENEMY_KINDS), L, i, list(rnd.choice([c for c in cells if c[0] >= 3 and list(c) not in [m[0]["position"] for m in members]]))))
    # occasional starting conditions to shift advantage / control branches
    for m in members:
        if rnd.random() < 0.08:
            m[0]["conditions"] = [rnd.choice(["prone", "blinded", "restrained", "poisoned", "frightened"])]
    grid = open_grid(W, 10)
    if rnd.random() < 0.3:
        grid["walls"] = [[W // 2, y] for y in range(10) if y not in (4, 5)]
    sc = scenario("sweep-%03d" % n, rnd.randint(1, 2**31), grid, members, cap=3)
    sc["plans"] = {m[0]["id"]: POL for m in members}
    sweep.append(sc)


for n in range(60):
    L = rnd.choice([5, 7, 9, 11])
    pb = PB(L)
    members = []
    bard = party_member("bard", L, 0, [1, 4])
    bard[0]["spellcasting"]["spells"] = ["haste", "bless"]
    bard[0]["spellcasting"]["slots"] = SLOTS[L]
    bard[0].pop("startHp", None)
    members.append(bard)
    for i in range(1, rnd.randint(3, 4)):
        dmg = rnd.choice([[1, 8, 3], [1, 12, 4], [2, 6, 4], [2, 8, 5]])
        m = cb("p%d" % i, "party", L, [16, 12, 14, 10, 10, 10], 16, 9 * L, [2, 2 + 2 * i],
               [atk("sword", "melee", pb + 3, dmg, "slashing", reachFt=5)], POL, extraAttacks=rnd.choice([0, 1, 2]))
        members.append(m)
    for i in range(rnd.randint(2, 3)):
        members.append(enemy_member(rnd.choice(["orc", "ogre", "goblin", "brute"]), L, i, [rnd.randint(8, 14), rnd.randint(2, 7)]))
    sc = scenario("sweep-b%02d" % n, rnd.randint(1, 2**31), open_grid(16, 10), members, cap=3)
    sc["plans"] = {m[0]["id"]: POL for m in members}
    sweep.append(sc)

out = {
  "$comment": "Shared by tools/reference/gen-encounters.mts (TypeScript) and EncounterParityTest (Java). Dice are [count, sides, bonus]. See gen-encounters.mts for the plan intent grammar.",
  "spells": spells,
  "scenarios": [brawl, casters, boss, skirmish, pressure, ai_skirmish, ai_casters, ai_boss, ai_crowd],
}

(REF / "scenarios.json").write_text(json.dumps(out, indent=1) + "\n")
sweep_doc = {"$comment": "Generated by tools/reference/gen-scenarios.py; spells come from scenarios.json.", "scenarios": sweep}
raw = (json.dumps(sweep_doc, separators=(",", ":")) + "\n").encode()
with open(REF / "sweep.json.gz", "wb") as fh:
    with gzip.GzipFile(filename="", mode="wb", fileobj=fh, mtime=0, compresslevel=9) as gz:
        gz.write(raw)
print("wrote scenarios.json (%d scenarios) and sweep.json.gz (%d fights, %d bytes raw)" % (len(out["scenarios"]), len(sweep), len(raw)))
