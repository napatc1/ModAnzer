# ModAnzer

A Minecraft mod analyzer for Windows. It finds every mods folder on the PC by itself, so there is no path to paste, then checks each mod against Modrinth and Megabase and scans unknown mods for cheat module names.

Based on [HabibiModAnalyzer](https://github.com/HadronCollision/PowershellScripts) by HadronCollision.

## Run it

Open PowerShell and paste:

```powershell
powershell -ExecutionPolicy Bypass -NoProfile -Command "irm https://raw.githubusercontent.com/napatc1/ModAnzer/main/ModAnzer.ps1 | iex"
```

Safer option: download `ModAnzer.ps1`, read it, then run your local copy:

```powershell
powershell -ExecutionPolicy Bypass -File .\ModAnzer.ps1
```

When it starts, it asks which launcher you use:

```
  1) Scan everything (auto-detect)  [default]
  2) Minecraft Launcher (.minecraft, Badlion, SKLauncher)
  3) CurseForge
  4) Modrinth App
  5) Prism / MultiMC / PolyMC
  6) ATLauncher
  7) GDLauncher
  8) Lunar Client
  9) Dawn Client (formerly Feather)
 10) TLauncher
 11) Technic
 12) Other (type the path to your mods folder)
```

Press Enter for 1. If the launcher you pick has no mods folder in its usual place, it asks you to paste the path, or press Enter to scan everything.

### Options

| Option | What it does |
|---|---|
| `-Path "D:\pack\mods"` | Skip the menu and scan only these folders (can list several, comma-separated) |
| `-Offline` | Skip the online lookups; only do the cheat-string scan |

## Where it looks

- Vanilla launcher: `%APPDATA%\.minecraft` (including the Microsoft Store version)
- CurseForge: `~\curseforge\minecraft\Instances\*`
- Modrinth App: `%APPDATA%\ModrinthApp\profiles\*`
- Prism, PolyMC, MultiMC, ATLauncher, GDLauncher, Technic
- Dawn, formerly Feather (`%APPDATA%\.dawn`, and `.feather` with its `user-mods` folder), Lunar, TLauncher
- Any dot-folder in `%APPDATA%` or your user folder, such as `.badlion`
- The game folder of any Minecraft that is running right now (read from `--gameDir`)

Jars in one level of subfolders (for example `mods\1.21\`) are included too.

## What it does

1. Hashes every `.jar` with SHA1.
2. Looks the hashes up on Modrinth in one batched request, then on Megabase for anything Modrinth doesn't know. Only hashes leave your PC, never files.
3. Opens each unrecognised jar in memory, including jars nested in `META-INF/jars`, and searches class names and class contents for known cheat module names such as AutoCrystal, TriggerBot and KillAura. The match is case-sensitive, so ordinary words like "velocity" don't trigger it.
4. Prints the results grouped by launcher and instance: **[OK]** verified, **[?]** unknown (with the download URL Windows recorded, if any), and **[CHEAT]** with the strings that matched.

It is read-only: nothing is modified, uploaded, extracted to disk, or installed.

"Unknown" doesn't mean cheat. It only means the mod isn't on Modrinth or Megabase. A cheat hit is a name match, so check it by hand before acting on it.

## Changes from HabibiModAnalyzer

- Automatic discovery of mods folders across launchers and clients
- One batched Modrinth request instead of two requests per mod
- Each identical jar is checked once, even if it is in several instances
- In-memory scanning with no temp folder, and nested jars inside nested jars
- Case-sensitive matching to cut false positives, with duplicate strings removed and a few added
- Uptime shown for every running instance, along with its game folder
- A launcher menu with an "Other" option for any mods folder
