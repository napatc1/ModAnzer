<#
.SYNOPSIS
    ModAnzer - finds every Minecraft mods folder on this PC and checks the mods in it.

.DESCRIPTION
    Based on HabibiModAnalyzer by HadronCollision
    (https://github.com/HadronCollision/PowershellScripts).

    What it does:
      * Auto-detects mods folders for vanilla .minecraft, CurseForge, Modrinth App,
        Prism / PolyMC / MultiMC, ATLauncher, GDLauncher, Technic, Dawn (Feather), Lunar,
        and any "dot" client folder (.badlion, ...) - no path pasting needed.
      * Also picks up the game folder of any Minecraft that is running right now.
      * Hashes each .jar (SHA1) and looks the hashes up on Modrinth (batched) and
        Megabase. Only hashes are sent - never your files.
      * Mods that are not recognised are opened in memory (no temp extraction) and
        searched, including nested jars, for well-known cheat module names.

    Read-only: nothing is modified, uploaded, or installed.

.PARAMETER Path
    One or more folders to scan instead of auto-detection.

.PARAMETER Offline
    Skip the online lookups and only do the cheat-string scan.

.PARAMETER SkipIntro
    Don't show the "what this script does" notice or wait for Enter.

.PARAMETER NoPause
    Don't wait for Enter at the end (by default it waits, so the window
    stays open when started with right-click > Run with PowerShell).

.EXAMPLE
    .\ModAnzer.ps1
.EXAMPLE
    .\ModAnzer.ps1 -Path "D:\Games\MyPack\mods"
#>
[CmdletBinding()]
param(
    [string[]]$Path,
    [switch]$Offline,
    [switch]$SkipIntro,
    [switch]$NoPause
)

$ErrorActionPreference = 'Stop'
$ProgressPreference    = 'SilentlyContinue'
try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch {}
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

# ---------------------------------------------------------------- settings

# Matched case-sensitively, so ordinary words like "velocity" in normal mods don't trigger.
$CheatStrings = @(
    'AimAssist', 'AnchorTweaks', 'AutoAnchor', 'AutoCrystal', 'AutoDoubleHand',
    'AutoHitCrystal', 'AutoPot', 'AutoTotem', 'AutoArmor', 'InventoryTotem',
    'Hitboxes', 'JumpReset', 'LegitTotem', 'PingSpoof', 'SelfDestruct',
    'ShieldBreaker', 'TriggerBot', 'Velocity', 'AxeSpam', 'WebMacro', 'FastPlace',
    'KillAura', 'CrystalAura', 'AnchorAura', 'AutoClicker'
)
$CheatRegex = [regex]::new(($CheatStrings | ForEach-Object { [regex]::Escape($_) }) -join '|')

# Folder names that hold mods
$ModFolderNames = @('mods', 'user-mods')

# Dot-folders that are never Minecraft and are expensive to walk
$SkipDirs = @(
    '.git', '.gradle', '.m2', '.npm', '.nuget', '.cargo', '.rustup', '.android',
    '.vscode', '.vscode-server', '.cache', '.docker', '.conda', '.ipython',
    '.dotnet', '.templateengine', 'node_modules', 'assets', 'libraries',
    'versions', 'logs', 'crash-reports', 'screenshots', 'saves', 'resourcepacks',
    'shaderpacks', 'natives', 'jre', 'runtime', 'java'
)

$MaxDepth       = 6          # how deep to look for a mods folder under each root
$MaxEntryBytes  = 8MB        # skip unusually large entries inside a jar
$UserAgent      = 'ModAnzer (github.com/napatc1/ModAnzer)'

# ---------------------------------------------------------------- helpers

function Write-Section($text) {
    Write-Host "{ $text }" -ForegroundColor DarkCyan
}

function Get-ShortPath([string]$p) {
    foreach ($pair in @(@($env:APPDATA, '%APPDATA%'), @($env:LOCALAPPDATA, '%LOCALAPPDATA%'), @($env:USERPROFILE, '~'))) {
        if ($pair[0] -and $p.StartsWith($pair[0], [StringComparison]::OrdinalIgnoreCase)) {
            return $pair[1] + $p.Substring($pair[0].Length)
        }
    }
    return $p
}

function Get-LauncherName([string]$p) {
    $l = $p.Replace('/', '\').ToLowerInvariant()
    switch -Regex ($l) {
        'curseforge|\\twitch\\'      { return 'CurseForge' }
        'modrinthapp|theseus'        { return 'Modrinth' }
        'prismlauncher'              { return 'Prism' }
        'polymc'                     { return 'PolyMC' }
        'multimc'                    { return 'MultiMC' }
        'atlauncher'                 { return 'ATLauncher' }
        'gdlauncher'                 { return 'GDLauncher' }
        '\\\.technic\\'              { return 'Technic' }
        '\\\.(dawn|feather)\\'       { return 'Dawn' }
        '\\\.lunarclient\\'          { return 'Lunar' }
        '\\\.tlauncher\\'            { return 'TLauncher' }
        '\\\.minecraft\\'            { return 'Minecraft' }
        '\\\.([^\\]+)\\'             { return ".$($Matches[1])" }
    }
    return 'Custom'
}

# Walk a root looking for folders named mods / user-mods, without descending into junk.
function Find-ModFolders([string]$root, [int]$depth = 0) {
    if ($depth -gt $MaxDepth) { return }
    $children = $null
    try { $children = [IO.Directory]::GetDirectories($root) } catch { return }
    foreach ($dir in $children) {
        $name = [IO.Path]::GetFileName($dir)
        if ($ModFolderNames -contains $name.ToLowerInvariant()) {
            $dir
            continue
        }
        if ($SkipDirs -contains $name.ToLowerInvariant()) { continue }
        try {
            if (([IO.File]::GetAttributes($dir) -band [IO.FileAttributes]::ReparsePoint)) { continue }
        } catch { continue }
        Find-ModFolders $dir ($depth + 1)
    }
}

# Game directories of any Minecraft that is running right now (--gameDir argument).
function Get-RunningGameDirs {
    $result = @()
    try {
        $procs = Get-CimInstance Win32_Process -Filter "Name='javaw.exe' OR Name='java.exe'" -ErrorAction Stop
    } catch { return $result }
    foreach ($p in $procs) {
        if ($p.CommandLine -match '--gameDir\s+(?:"([^"]+)"|(\S+))') {
            $dir = if ($Matches[1]) { $Matches[1] } else { $Matches[2] }
            $result += [PSCustomObject]@{ Pid = $p.ProcessId; Dir = $dir; Started = $p.CreationDate }
        }
    }
    return $result
}

function Get-ZoneIdentifier([string]$filePath) {
    try {
        $ads = Get-Content -Raw -Stream Zone.Identifier -LiteralPath $filePath -ErrorAction Stop
        if ($ads -match 'HostUrl=(.+)') { return $Matches[1].Trim() }
    } catch {}
    return $null
}

function Read-EntryBytes([System.IO.Compression.ZipArchiveEntry]$entry) {
    $ms = New-Object IO.MemoryStream
    $s = $entry.Open()
    try { $s.CopyTo($ms) } finally { $s.Dispose() }
    return $ms.ToArray()
}

# Search a jar (from a stream) for cheat strings: entry names, then file contents, then nested jars.
function Search-Jar([IO.Stream]$stream, [string]$label, [System.Collections.Generic.List[object]]$hits, [int]$level = 0) {
    $latin1 = [Text.Encoding]::GetEncoding(28591)
    $zip = $null
    try { $zip = New-Object IO.Compression.ZipArchive($stream, [IO.Compression.ZipArchiveMode]::Read, $true) }
    catch { return }
    try {
        $found = New-Object 'System.Collections.Generic.HashSet[string]'
        foreach ($entry in $zip.Entries) {
            foreach ($m in $CheatRegex.Matches($entry.FullName)) { [void]$found.Add($m.Value) }

            if ($entry.Length -eq 0 -or $entry.Length -gt $MaxEntryBytes) { continue }
            $lower = $entry.FullName.ToLowerInvariant()

            if ($lower.EndsWith('.jar') -and $level -lt 3) {
                $bytes = Read-EntryBytes $entry
                $inner = New-Object IO.MemoryStream(,$bytes)
                Search-Jar $inner "$label -> $([IO.Path]::GetFileName($entry.FullName))" $hits ($level + 1)
                $inner.Dispose()
                continue
            }
            if ($lower.EndsWith('.class') -or $lower.EndsWith('.json') -or $lower.EndsWith('.toml') -or $lower.EndsWith('.mf')) {
                $text = $latin1.GetString((Read-EntryBytes $entry))
                foreach ($m in $CheatRegex.Matches($text)) { [void]$found.Add($m.Value) }
            }
        }
        if ($found.Count -gt 0) {
            $hits.Add([PSCustomObject]@{ Where = $label; Strings = (@($found) | Sort-Object) })
        }
    } finally { $zip.Dispose() }
}

function Wait-BeforeClose {
    if (-not $NoPause) {
        Write-Host
        Read-Host 'Press Enter to close' | Out-Null
    }
}

function Invoke-Json([string]$uri, [string]$method = 'Get', $body = $null) {
    $params = @{ Uri = $uri; Method = $method; UseBasicParsing = $true; UserAgent = $UserAgent; TimeoutSec = 20 }
    if ($body) { $params.Body = $body; $params.ContentType = 'application/json' }
    return Invoke-RestMethod @params
}

# ---------------------------------------------------------------- 1. find folders

Clear-Host
Write-Host 'ModAnzer' -ForegroundColor Yellow -NoNewline
Write-Host '  - Minecraft mod analyzer (based on HabibiModAnalyzer by HadronCollision)' -ForegroundColor DarkGray
Write-Host

if (-not $SkipIntro) {
    Write-Host 'This is NOT a hacking or malware tool. It is read-only and only:' -ForegroundColor White
    Write-Host '  - Finds your Minecraft mods folders (or the one you choose)' -ForegroundColor Gray
    Write-Host '  - Checks which Minecraft is running and how long it has been open' -ForegroundColor Gray
    Write-Host '  - Makes a fingerprint (SHA1 hash) of each .jar mod file' -ForegroundColor Gray
    Write-Host '  - Looks those fingerprints up on Modrinth and Megabase to name your mods' -ForegroundColor Gray
    Write-Host '  - Searches mods it does not recognise for known cheat names' -ForegroundColor Gray
    Write-Host '  - Shows the results on this screen' -ForegroundColor Gray
    Write-Host
    Write-Host 'It does NOT:' -ForegroundColor White
    Write-Host '  - Upload your files, or send anything except the mod fingerprints' -ForegroundColor Gray
    Write-Host '  - Read passwords, tokens, browser data, Discord or launcher accounts' -ForegroundColor Gray
    Write-Host '  - Change, move or delete any files' -ForegroundColor Gray
    Write-Host '  - Install anything or change any settings' -ForegroundColor Gray
    Write-Host
    Write-Host 'Source code: https://github.com/napatc1/ModAnzer' -ForegroundColor DarkGray
    Write-Host
    Read-Host 'Press Enter to start (or Ctrl+C to quit)' | Out-Null
    Write-Host
}

$running = @(Get-RunningGameDirs)
if ($running.Count -gt 0) {
    Write-Section 'Running Minecraft'
    foreach ($r in $running) {
        $up = ''
        if ($r.Started) {
            $t = (Get-Date) - $r.Started
            $up = ' - up {0}h {1}m {2}s' -f [int][math]::Floor($t.TotalHours), $t.Minutes, $t.Seconds
        }
        Write-Host "> PID $($r.Pid)$up" -ForegroundColor White
        Write-Host "  $(Get-ShortPath $r.Dir)" -ForegroundColor Gray
    }
    Write-Host
}

$folders = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)

function Fix-Sep([string]$p) { return $p.Replace('\', [IO.Path]::DirectorySeparatorChar) }

$home_ = $env:USERPROFILE
$Launchers = @(
    @{ Name = 'Minecraft Launcher (.minecraft, Badlion, SKLauncher)'; Roots = @("$env:APPDATA\.minecraft") },
    @{ Name = 'CurseForge';            Roots = @("$home_\curseforge\minecraft\Instances", "$home_\Documents\curseforge\minecraft\Instances", "$home_\Twitch\Minecraft\Instances") },
    @{ Name = 'Modrinth App';          Roots = @("$env:APPDATA\ModrinthApp", "$env:APPDATA\com.modrinth.theseus") },
    @{ Name = 'Prism / MultiMC / PolyMC'; Roots = @("$env:APPDATA\PrismLauncher", "$env:APPDATA\PolyMC", "$env:LOCALAPPDATA\Programs\PrismLauncher", "$env:LOCALAPPDATA\Programs\MultiMC") },
    @{ Name = 'ATLauncher';            Roots = @("$env:APPDATA\ATLauncher") },
    @{ Name = 'GDLauncher';            Roots = @("$env:APPDATA\gdlauncher_next", "$env:APPDATA\gdlauncher_carbon") },
    @{ Name = 'Lunar Client';          Roots = @("$home_\.lunarclient") },
    @{ Name = 'Dawn Client (formerly Feather)'; Roots = @("$env:APPDATA\.dawn", "$home_\.dawn", "$env:APPDATA\.feather") },
    @{ Name = 'TLauncher';             Roots = @("$env:APPDATA\.tlauncher", "$env:APPDATA\.minecraft") },
    @{ Name = 'Technic';               Roots = @("$env:APPDATA\.technic") }
)

# Add the mods folders found under each root
function Add-FromRoots($rootList) {
    foreach ($root in $rootList) {
        if (-not $root) { continue }
        $root = Fix-Sep $root
        if (-not (Test-Path -LiteralPath $root -PathType Container)) { continue }
        $leaf = [IO.Path]::GetFileName($root.TrimEnd('\', '/'))
        if ($ModFolderNames -contains $leaf.ToLowerInvariant()) { [void]$folders.Add($root); continue }
        foreach ($f in (Find-ModFolders $root)) { [void]$folders.Add($f) }
    }
}

# A folder the user typed: use it if it holds jars, otherwise look for mods folders inside it
function Add-UserFolder([string]$p) {
    $p = $p.Trim().Trim('"').Trim("'")
    if ($p -and -not (Test-Path -LiteralPath $p -PathType Container) -and (Test-Path -LiteralPath (Fix-Sep $p) -PathType Container)) { $p = Fix-Sep $p }
    if (-not $p -or -not (Test-Path -LiteralPath $p -PathType Container)) { return $false }
    $p = (Resolve-Path -LiteralPath $p).ProviderPath
    $before = $folders.Count
    if (Get-ChildItem -LiteralPath $p -Filter *.jar -File -Recurse -Depth 1 -ErrorAction SilentlyContinue | Select-Object -First 1) {
        [void]$folders.Add($p)
    }
    foreach ($f in (Find-ModFolders $p)) { [void]$folders.Add($f) }
    if ($folders.Count -eq $before) { [void]$folders.Add($p) }
    return $true
}

if ($Path) {
    foreach ($p in $Path) {
        if (-not (Add-UserFolder $p)) { Write-Host "Not a folder: $p" -ForegroundColor Red }
    }
} else {
    $otherNum = $Launchers.Count + 2
    Write-Host 'Which launcher do you use?' -ForegroundColor White
    Write-Host '  1) ' -ForegroundColor Yellow -NoNewline; Write-Host 'Scan everything (auto-detect)  ' -NoNewline; Write-Host '[default]' -ForegroundColor DarkGray
    for ($n = 0; $n -lt $Launchers.Count; $n++) {
        Write-Host ('{0,3}) ' -f ($n + 2)) -ForegroundColor Yellow -NoNewline; Write-Host $Launchers[$n].Name
    }
    Write-Host ('{0,3}) ' -f $otherNum) -ForegroundColor Yellow -NoNewline; Write-Host 'Other (type the path to your mods folder)'
    Write-Host

    $choice = $null
    while ($null -eq $choice) {
        $in = (Read-Host "Choose 1-$otherNum").Trim()
        if ($in -eq '') { $in = '1' }
        $num = 0
        if ([int]::TryParse($in, [ref]$num) -and $num -ge 1 -and $num -le $otherNum) { $choice = $num }
        else { Write-Host "Type a number from 1 to $otherNum." -ForegroundColor Red }
    }
    Write-Host

    if ($choice -eq $otherNum) {
        while ($true) {
            $typed = Read-Host 'Path to the mods folder'
            if (Add-UserFolder $typed) { break }
            Write-Host 'That folder does not exist. Try again (you can paste it from File Explorer).' -ForegroundColor Red
        }
    } elseif ($choice -ge 2) {
        $pick = $Launchers[$choice - 2]
        Write-Host "[*] Looking for $($pick.Name) mods folders..." -ForegroundColor Yellow -NoNewline
        Add-FromRoots $pick.Roots
        if ($folders.Count -eq 0) {
            Write-Host "`r$(' ' * 70)`r" -NoNewline
            Write-Host "No $($pick.Name) mods folder found in the usual place." -ForegroundColor Red
            while ($true) {
                $typed = Read-Host 'Paste the path to your mods folder (or press Enter to scan everything)'
                if (-not $typed.Trim()) { $choice = 1; break }
                if (Add-UserFolder $typed) { break }
                Write-Host 'That folder does not exist. Try again.' -ForegroundColor Red
            }
        }
    }

    if ($choice -eq 1) {
        Write-Host '[*] Looking for mods folders...' -ForegroundColor Yellow -NoNewline
        $all = @()
        foreach ($l in $Launchers) { $all += $l.Roots }

        # Microsoft Store launcher
        Get-ChildItem -Path (Fix-Sep "$env:LOCALAPPDATA\Packages") -Directory -Filter 'Microsoft.4297127D64EC6_*' -ErrorAction SilentlyContinue |
            ForEach-Object { $all += (Join-Path $_.FullName 'LocalCache\Roaming\.minecraft') }

        # Any dot-folder in AppData\Roaming or the user folder (.dawn, .badlion, other clients)
        foreach ($base in @($env:APPDATA, $home_)) {
            if (-not $base) { continue }
            Get-ChildItem -LiteralPath $base -Directory -Force -ErrorAction SilentlyContinue |
                Where-Object { $_.Name.StartsWith('.') -and ($SkipDirs -notcontains $_.Name.ToLowerInvariant()) } |
                ForEach-Object { $all += $_.FullName }
        }

        # Running game directories
        foreach ($r in $running) { $all += $r.Dir }

        $seen = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
        Add-FromRoots (@($all | Where-Object { $_ -and $seen.Add((Fix-Sep $_)) }))
    }
}

# Collect jars (also one level of subfolders, e.g. mods\1.21\)
$jars = @()
foreach ($f in $folders) {
    $files = @(Get-ChildItem -LiteralPath $f -Filter *.jar -File -Recurse -Depth 1 -ErrorAction SilentlyContinue)
    foreach ($j in $files) {
        $jars += [PSCustomObject]@{ Folder = $f; File = $j }
    }
}

Write-Host "`r$(' ' * 60)`r" -NoNewline
$withJars = @($jars | Select-Object -ExpandProperty Folder -Unique)

if ($withJars.Count -eq 0) {
    Write-Host 'No mods found.' -ForegroundColor Red
    Write-Host 'Run it again and choose "Other" to type the path to your mods folder.' -ForegroundColor DarkGray
    Wait-BeforeClose
    exit 1
}

Write-Section "Mod folders found ($($withJars.Count))"
foreach ($f in $withJars) {
    $count = @($jars | Where-Object { $_.Folder -eq $f }).Count
    Write-Host ("> {0,-11}" -f (Get-LauncherName $f)) -ForegroundColor White -NoNewline
    Write-Host ("{0,4} jars  " -f $count) -ForegroundColor DarkGray -NoNewline
    Write-Host (Get-ShortPath $f) -ForegroundColor Gray
}
Write-Host

# ---------------------------------------------------------------- 2. hash

$i = 0
foreach ($j in $jars) {
    $i++
    Write-Host "`r[*] Hashing $i / $($jars.Count)" -ForegroundColor Yellow -NoNewline
    try { $h = (Get-FileHash -LiteralPath $j.File.FullName -Algorithm SHA1).Hash.ToLowerInvariant() } catch { $h = $null }
    $j | Add-Member -NotePropertyName Hash -NotePropertyValue $h
}
$hashes = @($jars | Where-Object { $_.Hash } | Select-Object -ExpandProperty Hash -Unique)

# ---------------------------------------------------------------- 3. look up

$known = @{}   # hash -> mod name

if (-not $Offline -and $hashes.Count -gt 0) {
    Write-Host "`r[*] Checking $($hashes.Count) unique mods on Modrinth...      " -ForegroundColor Yellow -NoNewline
    $projectIdByHash = @{}
    try {
        for ($n = 0; $n -lt $hashes.Count; $n += 500) {
            $chunk = @($hashes[$n..([math]::Min($n + 499, $hashes.Count - 1))])
            $body  = @{ hashes = $chunk; algorithm = 'sha1' } | ConvertTo-Json -Compress
            $resp  = Invoke-Json 'https://api.modrinth.com/v2/version_files' 'Post' $body
            foreach ($prop in $resp.PSObject.Properties) {
                if ($prop.Value.project_id) { $projectIdByHash[$prop.Name.ToLowerInvariant()] = $prop.Value.project_id }
            }
        }
        $ids = @($projectIdByHash.Values | Select-Object -Unique)
        $titleById = @{}
        for ($n = 0; $n -lt $ids.Count; $n += 100) {
            $chunk = @($ids[$n..([math]::Min($n + 99, $ids.Count - 1))])
            $q = [uri]::EscapeDataString((ConvertTo-Json -InputObject $chunk -Compress))
            foreach ($proj in (Invoke-Json "https://api.modrinth.com/v2/projects?ids=$q")) { $titleById[$proj.id] = $proj.title }
        }
        foreach ($h in $projectIdByHash.Keys) {
            $t = $titleById[$projectIdByHash[$h]]
            if (-not $t) { $t = '(Modrinth project)' }
            $known[$h] = "$t"
        }
    } catch {
        Write-Host "`r[!] Modrinth lookup failed: $($_.Exception.Message)" -ForegroundColor Red
    }

    $left = @($hashes | Where-Object { -not $known.ContainsKey($_) })
    $i = 0
    foreach ($h in $left) {
        $i++
        Write-Host "`r[*] Checking Megabase $i / $($left.Count)              " -ForegroundColor Yellow -NoNewline
        try {
            $r = Invoke-Json "https://megabase.vercel.app/api/query?hash=$h"
            if (-not $r.error -and $r.data -and $r.data.name) { $known[$h] = "$($r.data.name)" }
        } catch {}
    }
}

# ---------------------------------------------------------------- 4. scan unknown jars

$scanned = @{}   # hash -> list of hits
$unknownHashes = @($hashes | Where-Object { -not $known.ContainsKey($_) })
$i = 0
foreach ($h in $unknownHashes) {
    $i++
    Write-Host "`r[*] Scanning unknown mods for cheat strings $i / $($unknownHashes.Count)      " -ForegroundColor Yellow -NoNewline
    $jar  = ($jars | Where-Object { $_.Hash -eq $h } | Select-Object -First 1).File
    $hits = New-Object 'System.Collections.Generic.List[object]'
    try {
        $fs = [IO.File]::OpenRead($jar.FullName)
        try { Search-Jar $fs $jar.Name $hits } finally { $fs.Dispose() }
    } catch {
        $hits.Add([PSCustomObject]@{ Where = $jar.Name; Strings = @("could not open: $($_.Exception.Message)") })
    }
    $scanned[$h] = $hits
}

Write-Host "`r$(' ' * 80)`r" -NoNewline

# ---------------------------------------------------------------- 5. report

$totalCheat = 0; $totalUnknown = 0; $totalVerified = 0

foreach ($f in $withJars) {
    Write-Section "$(Get-LauncherName $f)  $(Get-ShortPath $f)"
    $here = @($jars | Where-Object { $_.Folder -eq $f } | Sort-Object { $_.File.Name })

    foreach ($j in $here) {
        $name = $j.File.Name
        if ($j.Hash -and $known.ContainsKey($j.Hash)) {
            $totalVerified++
            Write-Host ('  [OK]    {0,-32}' -f $known[$j.Hash]) -ForegroundColor Green -NoNewline
            Write-Host $name -ForegroundColor DarkGray
            continue
        }
        $hits = $null
        if ($j.Hash) { $hits = $scanned[$j.Hash] }
        if ($hits -and $hits.Count -gt 0) {
            $totalCheat++
            Write-Host "  [CHEAT] $name" -ForegroundColor Red
            foreach ($hit in $hits) {
                Write-Host "          $($hit.Where): " -ForegroundColor Gray -NoNewline
                Write-Host ($hit.Strings -join ', ') -ForegroundColor Magenta
            }
        } else {
            $totalUnknown++
            Write-Host "  [?]     $name" -ForegroundColor DarkYellow
            $zone = Get-ZoneIdentifier $j.File.FullName
            if ($zone) { Write-Host "          downloaded from $zone" -ForegroundColor DarkGray }
        }
    }
    Write-Host
}

Write-Section 'Summary'
Write-Host "  Verified: $totalVerified" -ForegroundColor Green
Write-Host "  Unknown:  $totalUnknown" -ForegroundColor DarkYellow
Write-Host "  Cheat:    $totalCheat"   -ForegroundColor $(if ($totalCheat -gt 0) { 'Red' } else { 'Gray' })
if ($Offline) { Write-Host '  (offline mode - no online lookups were made)' -ForegroundColor DarkGray }
Write-Host
Write-Host 'Unknown does not mean cheat - it just is not on Modrinth/Megabase. Cheat hits are name matches; check them by hand.' -ForegroundColor DarkGray
Wait-BeforeClose
