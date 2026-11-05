# Commands

Client-side chat commands (Meteor prefix `.`, registered via `Commands.add()` in
`MaceKillAddon.onInitialize`, dispatched on the client thread like Meteor's own
`VClipCommand` — safe to send movement packets directly).

## `.tp <player>` — `commands/TpCommand.java` (aliases: `.qzrtp`)

Teleport to a named player via stepped `PositionAndOnGround` packets (through
walls/ceilings — position packets are not collision-checked). Uses Meteor's
`PlayerArgumentType`, so tab-completion suggests online players.

- Works standalone — the `PlayerTp` module does not need to be enabled.
- Uses the module's `Move Step` setting for packet chunking (default 8).
- Refuses self-targets; errors when not in game or the name doesn't resolve.
- Same ban profile as the mace teleports: long jumps flag vanilla/Paper/Grim.
  Raise `Move Step` to cut packet count, test-server first.

Example: `.tp Notch`

## `.blacklist add/remove/dis/list` — `commands/BlacklistCommand.java`

Manage the `Blacklist` module from chat (client-side only; the command changes
module settings and sends no packets itself):

- `.blacklist add <player>` — add a name to the kill list. Online names
  tab-complete and are stored with exact capitalization; unknown/offline names are
  accepted as-typed (matching is case-insensitive). Duplicates are rejected.
- `.blacklist remove <player>` — remove a name; tab-completes the current entries.
- `.blacklist dis <value>` — scan radius in blocks, `0-256`, default **60**. Writes
  the module's `Scan Distance` setting.
- `.blacklist list` (or bare `.blacklist`) — entries, scan distance, and whether
  the module is enabled.

Entries live in the module's `Names` list (`StringListSetting`), so they survive
restarts with Meteor's module save. While the `Blacklist` module is enabled it
smashes the first blacklisted player inside the radius via UltraAura's shared
`smash(Entity)` code — same teleport packet profile as `UltraAura`, so the
`.tp`/`.tpmace` ban warnings apply.
