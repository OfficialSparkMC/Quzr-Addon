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
