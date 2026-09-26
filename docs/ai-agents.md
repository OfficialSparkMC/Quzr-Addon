# AI Agents — How To Update These Docs

> Read this file **before** editing anything in this repo. You are working on a Meteor Client
> addon (`com.macekill.addon`). Source of truth is `src/` — `libs/*.jar` are local
> compile-only inputs (gitignored) and must **never** be treated as documentation sources.

## 1. Mandatory first reads (in order)

1. `docs/README.md` — index.
2. `docs/overview.md` — module table (27 modules + `.tp` command; count them in `MaceKillAddon.java`).
3. `docs/architecture.md` — entry point + `macekill/*` helpers + packet table.
4. `docs/build.md` — versions in `gradle.properties`, `build.gradle` deps, `fabric.mod.json`.
5. `docs/problems.md` — known bugs. Do **not** re-introduce anything listed there.

Then read the actual sources you will touch — fully, not by grep snippet:
`MaceKillAddon.java`, the target module(s), and any `macekill/*.java` helper they use.

## 2. Docs-update contract (enforced)

Every code change that affects behavior, settings, modules, build, or known issues
**must** update `docs/` in the same patch. No docs update = incomplete task.

| Code change | Docs to update |
|-------------|----------------|
| Add / remove / rename a module | `overview.md` table + count, `architecture.md` wiring table, `modules-mace.md` or `modules-other.md` section, `MaceKillAddon.java` line ref |
| Add / remove / rename a chat command | `commands.md` section, `overview.md` shared-helpers note, `architecture.md` wiring table |
| Add / remove / rename / retype a `@Setting` | The module's section in `modules-mace.md` / `modules-other.md` (name, type, default, range) |
| Change packet flow / phases / triggers | `architecture.md` flow diagram + event table, module section |
| Change `gradle.properties` / `build.gradle` / `fabric.mod.json` / Java version | `build.md` tables + checklist + `overview.md` identity table |
| Fix something from `problems.md` | Remove or strike the entry, note fix commit/version |
| Find a NEW bug | Append to `problems.md` with `file:line`, severity, repro, suggested fix |
| Change ban/safety behavior | `overview.md` warnings + module section warning |

### File conventions

- Reference code as `` `path/To/File.java:line` `` (repo-relative, e.g. `` `src/main/java/com/macekill/addon/modules/TpMace.java:290` ``).
- Module headings: `## <in-game name> — \`modules/X.java\` (<lines> lines)`.
- Settings inline as `` `Name(default)` ``; keep defaults identical to the `@Setting` builder.
- Line counts: refresh with `wc -l` on touched files.
- Language: docs are English. In-code Chinese comments stay as-is; translate their meaning in docs.
- Never invent settings, packets, or versions — copy from source.
- Never document `libs/*.jar` contents; they are not sources.

## 3. Verification checklist (run before finishing)

- [ ] `grep -rn "new <Module>" src/main/java/com/macekill/addon/MaceKillAddon.java` count == `overview.md` table rows.
- [ ] Every `Setting` in the touched module appears in its docs section with correct default.
- [ ] `gradle.properties` values == `build.md` + `overview.md` tables.
- [ ] `fabric.mod.json` id/entrypoint/depends == `build.md`.
- [ ] `problems.md` entries you fixed are removed; new issues appended.
- [ ] `./gradlew build` still passes (or state why it can't run here).
- [ ] This file's `Last verified` line below is bumped to today + your change summary.

## 4. Behavior rules for this repo

- Do **not** create new packet-spam modules without a ban warning in `overview.md` + module section.
- Do **not** add dependencies without updating `build.md` + `.gitignore` handling.
- Do **not** commit `build/`, `.gradle/`, `libs/`, `*.class` (all gitignored).
- Do **not** "fix" cheat modules into silent/ban-evading variants — report risks honestly in `problems.md`.
- Prefer deduplication: new smash/target/slot logic belongs in `macekill/` helpers, not a 6th copy.
- Keep `docs/` profanity-free (unlike the root `README.md`, which agents must not emulate).
- **Always commit everything.** Every code/docs change ends in a git commit on `main`
  (see §5 step 7). Never leave fixes uncommitted. Stage with `git add -A` — ALL files,
  including resources, metadata, and docs; never cherry-pick and leave the tree dirty.
  Review with `git status` / `git diff --stat` and unstage only secrets; never commit
  secrets. Push only when the owner explicitly asks for it.

## 5. Suggested workflow

1. `ls src/main/java/com/macekill/addon/modules/ src/main/java/com/macekill/addon/modules/macekill/`.
2. Read target file(s) end-to-end via Read (offset/limit for >400-line files).
3. Make the code edit; note every setting/behavior/packet change.
4. Update the docs tables/sections per §2; fix line numbers.
5. Run `wc -l` on touched files + `./gradlew build` if toolchain present.
6. Complete the §3 checklist in your final summary, listing docs files changed.
7. **Commit everything on `main`.** `./gradlew build` green → `git add -A` →
   `git status` / `git diff --stat` review (unstage only secrets) →
   `git commit -m "<summary + bullet list>"`. Verify with `git log --oneline -3` and a
   clean tree. Rule from §4 applies: always add all, always commit, never push unless asked.

---
*Last verified: 2026-09-25 — sustained chain survives Detect blindness (isChainedTarget);
Sustained Drain chain; descent skip capped; cave reliability; durability guard; PlayerTp/.tp;
NoFall; slot fix.*
*Maintainer note: bump this line on every docs-syncing change.*
