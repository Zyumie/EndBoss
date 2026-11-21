<div align="center">

[![Paper](https://raw.githubusercontent.com/intergrav/devins-badges/v3/assets/cozy/supported/paper_vector.svg)](https://papermc.io/)
[![Purpur](https://raw.githubusercontent.com/intergrav/devins-badges/v3/assets/cozy/supported/purpur_vector.svg)](https://purpurmc.org/)

# ⚔️ EndBoss

### The Boss That Learns. The Fight Your Server Will Talk About.

**3 phases. 15 telegraphed abilities. An AI that adapts to every player.**

[![Minecraft](https://img.shields.io/badge/Minecraft-1.21%2B-47A248?style=flat-square&logo=minecraft&logoColor=white)](https://www.minecraft.net/)
[![Platform](https://img.shields.io/badge/Platform-Paper%20%7C%20Purpur-8A63D2?style=flat-square)](https://papermc.io/)
[![Java](https://img.shields.io/badge/Java-21-ED8B00?style=flat-square&logo=openjdk&logoColor=white)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-All%20Rights%20Reserved-5865F2?style=flat-square)](#-license)
</div>

---

## 📖 About

**EndBoss** is a full-scale boss fight for Paper servers: **The Guardian of the Void**, a Wither Skeleton armored in netherite, wielding a Mace and a Shield, and powered by a utility-based AI that reads the battlefield every tick.

It doesn't follow a script. It picks its target based on threat, distance, line of sight and low health. It chooses its next attack based on the situation, and it avoids repeating itself. It notices when you spam arrows, pillar up or fly with an elytra, and it adapts. Every attack is **telegraphed**, so the fight is brutally hard but always readable: a sharp player can always react.

While the boss lives, **the End portal and the Nether are sealed**. Your players have to earn their way through.

Whether you run a Survival server, a private SMP or a seasonal event, EndBoss gives you a centerpiece fight with cinematic intros, dramatic phase changes and a leaderboard to crown the best fighters.

### ✨ Main Highlights

- 🧠 Utility-score AI with target selection, attack variety and player-style learning.
- 🔥 **3 phases** with cinematic transitions, each stronger, bigger and faster.
- ⚠️ **15 abilities**, every heavy attack telegraphed with particle rings and action bar warnings.
- 🛡️ Frontal shield that **breaks under an axe**, projectile dodging and counter-attacks.
- 🌊 Water combat: it swims, hits harder and defends better in water, and can summon a whirlpool.
- ✦ **Totems of Undying** for multi-life boss fights.
- ☠️ **Impossible mode**: 5000 HP, 3 resurrections, built for a full coordinated group.
- 🏗️ Dynamic terrain: bridges, pillars, wall-breaking and anti-pillar, all **fully restored** afterwards with no drops.
- 🔒 Portal lock that survives restarts and unloaded chunks.
- 🏆 Boss bar, top 3 fighters announcement and custom loot tables.
- 📂 Everything configurable, with reusable **difficulty profiles**.

---

## 🧠 The AI

### It thinks

The boss evaluates its options **up to 20 times per second** (10 by default). Each ability gets a score based on distance, line of sight, whether you are on the ground, how many players are grouped together, and what it just did.

- 🎯 **Smart targeting:** threat, distance, visibility and low health all count.
- 🔁 **No spam:** it avoids repeating the same attack back to back.
- 📚 **Adaptive learning:** it spots archers, pillar-climbers and elytra users, then counters them.
- 🧱 **Terrain control:** it builds bridges to reach you, punches through walls and breaks your pillars. Everything is restored afterwards.
- 🩹 **Fair regeneration:** light regeneration in phases 2 and 3, and fast healing if you leave it alone for too long. Don't give it space.

### It defends

- 🛡️ **Shield:** blocks frontal hits. **Hit it with an axe to break the guard.**
- 🏹 **Dodges projectiles** (configurable).
- ⚔️ **Counter-attacks** right after a successful block.
- 💧 **Water:** in water it hits harder, takes less damage and recovers faster between attacks. Fight it on dry land.

---

## ⚔️ Abilities

Every heavy attack is telegraphed. Learn the tells, survive the fight.

| Ability | What it does | Unlocks |
|:---|:---|:---:|
| 🦘 **Leap** | Predictive jump onto where you *will* be, so kiting won't save you | Phase 1 |
| 💨 **Dash Strike** | A charging rush straight at you | Phase 1 |
| 🗡️ **Sword Throw** | Flying swords with limited homing (3 in phase 3). Change direction to dodge | Phase 1 |
| 🛡️ **Shield Up** | Raises the guard. An axe breaks it | Phase 1 |
| 🌀 **Blink** | Teleports right behind you | Phase 1 |
| 🪨 **Block Barrage** | Tears blocks from the ground and hurls them at you, more with each phase | Phase 1 |
| 🔨 **Mace Smash** | Leaps, hangs in the air over a locked zone, then crashes down. About 0.5 s to get out | Phase 2 |
| 🌊 **Shockwave** | An expanding ring. **Jump** to avoid it | Phase 2 |
| 👾 **Summon** | Calls "Void Shard" Vexes (capped by `minion-limit`) | Phase 2 |
| ⛰️ **Sky Slam** | Spikes erupt from the ground and launch anyone in the zone skyward | Phase 2 |
| 🫳 **Item Theft** | Steals the item in your hand. You get it back when the boss dies, when you die or when you log out | Phase 2 |
| 🌌 **Sky Rapture** | Rises into the sky, pulls nearby players up, then slams them back down | Phase 2 |
| 🎲 **Inventory Scramble** | Shuffles your inventory for a few seconds, then restores it | Phase 2 |
| ☄️ **Meteors** | A meteor rain: the first aims where you'll be, the rest cut off your escape routes | Phase 3 |
| 🌀 **Whirlpool** | An aquatic vortex, used when you're standing in water | In water |

*The "Unlocks" column shows the default `Boss.yml`. You can change the ability list of every phase.*

---

## 🔥 The Three Phases

| | Phase 1: Awakening | Phase 2: Wrath | Phase 3: Final Calamity |
|:---|:---:|:---:|:---:|
| Starts at | 100 % HP | 66 % HP | 33 % HP |
| Size | ×1.0 | ×1.35 | ×1.75 |
| Speed | ×1.0 | ×1.12 | ×1.25 |
| Damage | ×1.0 | ×1.25 | ×1.5 |
| Cooldowns | ×1.0 | ×0.85 | ×0.65 |
| Regeneration | none | 1.5 HP/s | 2.5 HP/s |

Each transition plays a cinematic. Every value above is configurable.

---

## ☠️ Impossible Mode

*Built for a whole server. Bring your best gear and a plan.*

```text
/endboss spawn impossible
```

| | Normal | ☠️ Impossible |
|:---|:---:|:---:|
| Health | 800 | **5000** |
| Damage taken | ×0.6 | **×0.35** |
| Shield damage let through | 12 % | **5 %** |
| Totems of Undying | none | **3** (back to phase 2 each time) |
| AI skill | 0.75 | **0.95** |
| AI tick rate | 10 / s | **20 / s** |
| Minion limit | 4 | **8** |
| Loot | none | Netherite ingots, diamond blocks, enchanted golden apples |

It is still beatable: chain your damage, break the guard with an axe and survive three resurrections.

### Totems of Undying

When a lethal hit lands, the boss is cancelled out of death, healed, and sent back to the configured phase. Totems are shown above its head and announced in chat. Enable them in any profile.

---

## 🔒 Dimension Lock

While the boss is alive:

- 🌌 **The End portal is sealed.**
- 🔥 **The Nether portal is sealed** (from the Overworld).

The lock state is saved to `state.yml`, so it survives restarts and unloaded chunks. Defeat the boss, or use `/endboss unlock`, to open the gates. Both locks and their messages are configurable.

---

## 🏆 Rewards & Recognition

- **Custom loot:** define your own drops in `boss.drops` (vanilla loot is always removed).
- **Top 3 fighters:** the best damage dealers are announced server-wide when the boss falls.
- **Boss bar:** shows the boss name, phase and health to everyone in range.
- **Cinematic death scene** and a server broadcast.

---

## 📋 Commands & Permissions

Permission: `endboss.admin` (op by default).

| Command | Description |
|:---|:---|
| `/endboss spawn [profile] [force]` | Summon the boss at your position. `profile` is `normal` (default), `impossible` or any custom profile. `force` ignores the single-instance limit |
| `/endboss kill` | Remove the boss and unlock the portals |
| `/endboss reload` | Reload `Boss.yml` and profiles without a restart |
| `/endboss status` | Show HP, phase, remaining totems and current target |
| `/endboss unlock` | Lift the portal lock manually |

---

## ⚙️ Configuration

EndBoss uses simple YAML files, and missing keys always fall back to sensible defaults.

| File | Purpose |
|:---|:---|
| `Boss.yml` | The default (normal) boss |
| `Impossible.yml` | The Impossible difficulty profile |
| `<Name>.yml` | Your own profiles, summoned with `/endboss spawn <name>` |
| `state.yml` | Internal: saved lock state |

### Custom difficulty profiles

Copy `Impossible.yml`, rename it (lowercase letters, numbers, `-` and `_`), tweak it and run `/endboss spawn <name>`. Any key you leave out inherits its value from `Boss.yml`.

### What you can configure

All keys live under `boss:`.

**General**
- `name`, `health`, `speed`, `damage`, `damage-taken-multiplier`, `ability-damage`, `single-instance`
- `equipment.*`: helmet, chestplate, leggings, boots, weapon, offhand
- `drops.*`: custom loot (`material` + `amount`)
- `display.overhead-format`: text above the head (`{hearts}`, `{hp}`, `{totems}`)
- `shield.damage-through`: share of damage that passes through the guard

**Locks**
- `end-lock.*` and `dimension-lock.*`: enable and customize the messages

**AI & combat**
- `ai.*`: `tick-interval`, `sight-range`, `bar-range`, `skill`, `adaptive-learning`, `dodge-projectiles`, `rest-regen-delay`, `rest-regen-percent`, `minion-limit`
- `totems.*`: `enabled`, `count`, `revive-to-phase`, `message`, `display`, `display-format`
- `water.*`: `swim-speed`, damage and cooldown multipliers, `whirlpool.*`
- `griefing.*`: `break-blocks`, `place-blocks`, `restore-seconds`, `protected-materials`
- `effects.*`: `enabled`, `view-distance`, `density`, `max-displays`

**Phases**
- `phases.phase-N.*`: `name-suffix`, `health-threshold`, `scale`, `speed-multiplier`, `damage-multiplier`, `cooldown-multiplier`, `regen-per-second`, `abilities`

### Protected blocks

The boss never breaks bedrock-like unbreakable blocks or containers, nor anything in `griefing.protected-materials` (the Dragon Egg, Beacons, End portal frames, End portals and End gateways by default). Everything it does break or place is restored after `restore-seconds`, **with no drops**.

---

## 🔌 Optional Integrations

EndBoss works on its own.

| Plugin | Integration |
|:---|:---|
| [PacketEvents](https://modrinth.com/plugin/packetevents) | Used for the visual effects when it is installed |

---

## 📥 Installation

### Requirements

- Minecraft Java Edition 1.21 or newer (built against the 1.21.11 API).
- Paper or Purpur server.
- Java 21.

### Installation steps

1. Build the plugin (see below) or grab a release `.jar`.
2. Stop your server and drop the jar into `plugins/`.
3. Start your server: `Boss.yml` and `Impossible.yml` are generated in `plugins/EndBoss/`.
4. Run `/endboss spawn` and enjoy.

> **Going above 1024 HP?** Paper caps maximum health at 1024 by default. For high-health profiles like Impossible (5000 HP), raise `settings.attribute.maxHealth.max` in `spigot.yml`. Otherwise the boss is capped and a warning appears in the console.

---

## 🛠️ Project Structure

```text
fr.zyumie.EndBoss
├── Main, EndBossRegistry, EndBossBar
├── IA/        EndBossBrain, EndBossCombat, EndBossMovement, EndBossScaffold,
│              EndBossWater, EndBossEffects, EndBossCinematics, EndBossAIController,
│              Ability, ThreatTable, PlayerProfile, BlockRestorer,
│              StolenItems, ScrambledInventories
└── Listener/  EndBossListener, EndBossManager, EndBossPhaseManager, VersionManager
```

---

## 🧩 Compatibility

EndBoss is developed for Paper and Purpur servers on Minecraft 1.21+.

Compatibility can depend on the Minecraft version and individual API changes. Check the release notes before updating a production server.

---

## 🐛 Bug Reports & Suggestions

Found a bug or have an idea for a new ability?

Please report it through the project's issue tracker, and include:

- Minecraft version.
- Server software and version.
- EndBoss version.
- Relevant console logs.
- Steps to reproduce the issue.

---

## 📄 License

**All Rights Reserved © Zyumie**

All rights reserved. Unauthorized copying, modification, redistribution or use of this software is prohibited without prior permission from the author.

---

<div align="center">

**Developed with ❤️ by Zyumie**

⚔️ EndBoss: One boss. Three phases. Zero mercy.

</div>