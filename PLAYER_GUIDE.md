# DiaryKeeper — SMP Player Guide

This file documents the player-facing diary behavior on Enthusia SMP. The main [`README.md`](README.md) remains the administrative recovery/duplicate-management reference.

The production values below were checked against the live Enthusia configuration and current source on August 22, 2026.

## Your diary

On a player's first join, DiaryKeeper issues:

1. that player's unique collectible diary; and
2. the current Enthusia welcome guide/book.

Each diary has a persistent internal diary ID and owner identity. The current appearance uses the custom Nexo `diary_book` base item, enchanted glint, the name:

```text
<player>'s Diary
```

and lore identifying it as a unique collectible diary together with its owner and shortened ID.

A normal player does not need a command to receive the diary. If initial delivery cannot fit safely in the player's inventory, the plugin uses its persistent delivery queue rather than silently deleting the item.

## Editing and ownership

The diary remains a **writable book** rather than becoming a signed/written book.

Current SMP rules:

- only the diary's recorded owner may edit its pages;
- signing the diary is disabled;
- text is restricted to the plugin's strict ASCII-safe character set;
- another player may possess/collect somebody else's diary, but they cannot rewrite that owner's diary;
- an anvil cannot be used to alter/process the diary;
- a grindstone cannot be used to alter/process the diary.

The purpose is for the owner's writing to remain associated with the one collectible diary without another holder being able to rewrite its identity/content.

## Trading and storage

Diaries are collectible items and can be dropped/transferred. The current production configuration allows ordinary dropping and ordinary container movement generally, but deliberately blocks several storage methods that would make tracking/duplicate recovery harder.

### Blocked storage

A diary cannot currently be stored inside:

- an Ender Chest;
- a Bundle;
- a Shulker Box;
- a LumaGuilds guild vault.

The nested-item checks also prevent bypassing these rules by putting a diary inside another tracked container item first and then trying to insert that container.

### Ordinary storage currently allowed

The DiaryKeeper configuration itself does not block diaries from ordinary:

- chests;
- barrels;
- hoppers;
- droppers;
- dispensers;
- furnaces.

Other server protection/plugin rules can still affect those containers independently.

## Destruction protection

The current SMP diary is intentionally very hard to destroy accidentally.

Dropped diary items are protected from:

- fire and burning;
- lava/fire damage;
- explosions;
- other normal contact/entity damage to the dropped item;
- normal item despawning.

This protection concerns the special DiaryKeeper item itself; it does not make the player holding it invulnerable.

## Dropping a diary into the void

DiaryKeeper tracks who last dropped a diary.

If a tracked dropped diary falls below the world's void threshold, the current server configuration removes the falling entity only **after** a durable return has been queued for the last player who dropped it. The diary is then returned through the same persistent delivery system.

So intentionally or accidentally throwing a diary into the void should not permanently destroy it. The return belongs to the **last dropper**, which matters if the diary had been traded/stolen and someone other than its original owner was carrying it.

If the recipient cannot accept it immediately, the delivery remains queued rather than being dropped unsafely on the ground.

## Duplicate protection

Each real diary is intended to be unique. DiaryKeeper continually tracks known locations/copies and can detect duplicate diary IDs in places such as:

- player inventories;
- Ender Chests;
- containers;
- nested Bundles/Shulkers;
- loaded chunks and relevant entities;
- queued deliveries.

The current server runs a background duplicate scan every **10 minutes** in repair mode, in addition to checks triggered by joins, container/chunk activity, and startup.

Duplicate detection is primarily a staff/recovery system. Players do not need to run repair commands themselves. Staff can purge extra copies and restore the canonical diary when necessary without intentionally changing its owner/content.

## First-join welcome guide

The separate welcome book issued with the diary gives new players a compact introduction to Enthusia SMP, including survival, world size, base safety, PvP/Warzone, Raw Gold economy, Market, homes, teleport requests, reputation, guilds, events, rules/Discord/wiki, and the website.

That welcome guide is a server onboarding item; it is separate from the uniquely tracked owner-editable diary.

## Bedrock support

DiaryKeeper has Floodgate-aware identity handling so Bedrock/Floodgate player identities remain stable for diary ownership/delivery tracking. The diary mechanic is not intended to be Java-only.

## What belongs on the public wiki

Useful player-facing wiki information from DiaryKeeper includes:

- everyone receives one unique owner-linked diary on first join;
- only the owner can edit it and it cannot be signed;
- diaries can be collected/traded but cannot be rewritten by another holder;
- blocked storage locations (Ender Chests, Bundles, Shulkers, guild vaults);
- destruction/despawn protection;
- void return to the last dropper;
- the fact that duplicates are tracked/repaired rather than legitimate copies being an intended mechanic.

Administrative purge operation states, scan budgets, storage formats, audit retention, and recovery internals should stay in repository/admin documentation instead of the player wiki.
