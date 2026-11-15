# Builds bin\RomCon.exe against the .NET Framework 4.8 that ships with Windows 10 and 11, so the exe needs no runtime install.
# Needs a Roslyn C# compiler: Visual Studio / VS Build Tools (any edition), or the .NET SDK.
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$out = Join-Path $root 'bin'
$fx = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319'
if (-not (Test-Path $fx)) { $fx = Join-Path $env:WINDIR 'Microsoft.NET\Framework\v4.0.30319' }

function Find-Compiler {
    $vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
    if (Test-Path $vswhere) {
        $csc = & $vswhere -latest -products * -prerelease -find 'MSBuild\**\Bin\Roslyn\csc.exe' | Select-Object -First 1
        if ($csc) { return @{ Exe = $csc; Args = @() } }
    }
    $dotnet = Get-Command dotnet -ErrorAction SilentlyContinue
    if ($dotnet) {
        $sdkRoot = Join-Path (Split-Path $dotnet.Source) 'sdk'
        $dll = Get-ChildItem $sdkRoot -Directory -ErrorAction SilentlyContinue | Sort-Object { [version]($_.Name -replace '-.*$', '') } -Descending |
            ForEach-Object { Join-Path $_.FullName 'Roslyn\bincore\csc.dll' } | Where-Object { Test-Path $_ } | Select-Object -First 1
        if ($dll) { return @{ Exe = $dotnet.Source; Args = @($dll) } }
    }
    throw 'No C# compiler found. Install "Visual Studio Build Tools" (C# workload) or the .NET SDK, then run this again.'
}

$icon = Join-Path $root 'romcon.ico'
if (-not (Test-Path $icon)) { & (Join-Path $root 'tools\make-icon.ps1') -Out $icon }

$compiler = Find-Compiler
New-Item -ItemType Directory -Force $out | Out-Null
$sources = Get-ChildItem (Join-Path $root 'src') -Filter *.cs | ForEach-Object { $_.FullName }
$refs = 'mscorlib.dll', 'System.dll', 'System.Core.dll', 'System.Drawing.dll', 'System.Windows.Forms.dll', 'System.Security.dll', 'System.Runtime.dll' |
    ForEach-Object { "/reference:$(Join-Path $fx $_)" }
# Windows' own descriptions of its media APIs ("what's playing"), which name their basic types through System.Runtime.
# They're only read at compile time, and they come with Windows, so no Windows SDK is needed. The price is that the
# framework's helpers for these APIs (AsTask, AsStream) can't be used: those are compiled against the SDK's one big Windows.winmd.
$refs += 'Windows.Foundation.winmd', 'Windows.Media.winmd', 'Windows.Storage.winmd' |
    ForEach-Object { "/reference:$(Join-Path $env:WINDIR "System32\WinMetadata\$_")" }

$cscArgs = $compiler.Args + @(
    '/nologo', '/noconfig', '/nostdlib+', '/target:winexe', '/platform:anycpu', '/optimize+', '/debug-',
    '/langversion:latest', '/warn:4', '/warnaserror-', '/utf8output',
    "/out:$(Join-Path $out 'RomCon.exe')",
    "/win32icon:$icon",
    "/win32manifest:$(Join-Path $root 'app.manifest')",
    "/resource:$icon,RomCon.app.ico"
) + $refs + $sources

& $compiler.Exe @cscArgs
if ($LASTEXITCODE -ne 0) { throw "Compile failed ($LASTEXITCODE)" }
$exe = Get-Item (Join-Path $out 'RomCon.exe')
Write-Host ("Built {0} ({1:N0} KB)" -f $exe.FullName, ($exe.Length / 1KB))
