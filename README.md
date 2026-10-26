# Vitals

SCP: Containment Breach style **stamina and injuries** for the SCP server (Paper 26.x, compiled against the 1.21.4 API, boot-tested on 26.3). RP Engine delivers the medical item models and the HUD icons.

## What it does

**Stamina bar** - its own boss bar at the top of the screen: an isolated row that never shares a line with the action bar, titles or scoreboards, and simply stacks with any other plugin's boss bars. Sprinting drains it (faster when hurt, bleeding or low on health); it refills while you walk. Run it dry and you are *winded*: no sprinting until it climbs back over `recover-at`.

**Injuries** (SCP:CB health model on top of vanilla health):

| Source | Wound | Effect |
|---|---|---|
| Firearms bullets (needs Firearms 0.18.2+) | Gunshot wound, severity by damage x caliber | bleeds until bandaged; leg hit = limp (no sprint), head hit = nausea |
| Firearms frag grenades, vanilla explosions | Shrapnel | small cuts, bleeding |
| Falls >= 6 hp (9 blocks) | Broken leg | very slow, no sprint, barely jumps - needs a splint |
| Smaller falls | Bruise | temporary slow, heals on its own |
| Fire / lava / magma / campfire | Burns | slow, fades very slowly (first aid kit clears) |
| SCP-106 (entity tag/name) or configured causes | Corrosion | acid keeps eating for 12 s, then a slow-fading wound |
| Swords, axes, tridents, arrows | Laceration | light bleeding, clots by itself |

- **Walk speed drops with lost health** (`speed.lost-health`) and with injury points (`speed.injury`), multiplied together; a broken leg and limping stack on top.
- **Sprint is blocked** under 30% health or with a broken leg.
- **Bleeding** drains health until a bandage; wounds under 1 injury point clot by themselves. Bleeding out gives a "bled out" death message.
- **No vanilla regen while wounded** (configurable) - like the game, you need a kit.
- **Low health**: tunnel vision (Darkness) under 35%, nausea + heartbeat under 20%.
- **Condition bar** - a second boss bar that only appears while hurt (lists the wounds) or while applying a medical item (progress).
- Injuries persist across relogs (player PDC) and reset on death.

**Medical items** (hold right-click; moving or switching items interrupts):

| Item | Time | Does |
|---|---|---|
| Bandage | 2 s | stops all bleeding, +2 hp |
| First Aid Kit | 4 s | stops bleeding + acid, takes 3 points off every wound, +6 hp |
| Splint | 3 s | fixes a broken leg |

Right-clicking SCP-500 (custom_model_data `scp500`, see `medical.cure-all-model-strings`) cures everything.

## Commands

```
/vitals status [player]        health / stamina / injuries / speed
/vitals hud on|off
/vitals give <bandage|first_aid_kit|splint> [player] [n]   (vitals.admin)
/vitals heal [player]
/vitals injure <player> <gunshot|cut|fracture|bruise|burn|corrosion> [severity] [bleed] [zone]
/vitals stamina <player> <0-100>
/vitals reload
```

## RP Engine

On first start Vitals writes `plugins/RPEngine/content/vitals/`: `pack.yml`, `items/vitals.yml` (the three items as RP Engine items), `fonts/icons.yml` (`:vitals:stamina:` and `:vitals:injury:` for the bars) and placeholder PNGs. Replace the PNGs, or give an item a Blockbench model (`assets/models/<name>.bbmodel` + `model: <name>` in `items/vitals.yml`), then `/rp reload`. Vitals never overwrites files that already exist there. Without RP Engine the items are plain tagged items and the bars have no icons.

## Labra

Labra has its own sprint stamina (action-bar meter). Only one plugin may own sprinting, so set in `plugins/Labra/config.yml` (Labra 0.57.1+):

```yaml
sprint:
  enabled: false
```

Vitals warns on startup (console + admins on join) while it is still on.

## Build

`mvn package` (Java 21). RP Engine and Firearms jars are compile-only dependencies in `libs/`.
