# Prints what the built installer will actually do, read out of the MSI tables.
#
# The installer needs elevation, so this is how the package is checked on a
# machine where it cannot be installed: the file list, the camera's COM
# registration, the firewall exception, the wizard's finish action, the
# Start-menu shortcut and the uninstall entries all come from the package
# database rather than from a build log.
#
# Two Windows Installer quirks are handled here. MSI SQL reserves words such as
# Property and Directory, so those names are quoted with backticks inside
# single-quoted PowerShell strings. And a Record exposes StringData to late
# binding but not FieldCount, so the count comes off the type. Rows come back as
# pipe-delimited strings, which sidesteps PowerShell's habit of unwrapping nested
# arrays returned from a function.

param(
    [string]$Msi = (Join-Path $PSScriptRoot 'bin\x64\Release\OpenCamSetup.msi')
)

if (-not (Test-Path $Msi)) {
    Write-Error "Not found: $Msi`nBuild it first with build.bat."
    exit 1
}

$installer = New-Object -ComObject WindowsInstaller.Installer
$database = $installer.OpenDatabase($Msi, 0)

function Query([string]$sql) {
    try {
        $view = $database.OpenView($sql)
    } catch {
        Write-Host "  query failed: $sql" -ForegroundColor Red
        return
    }

    # Execute and Close return values that would otherwise land in this
    # function's output, so they are swallowed explicitly.
    $null = $view.Execute()

    $out = @()
    while ($true) {
        $record = $view.Fetch()
        if (-not $record) { break }

        $count = [int]$record.GetType().InvokeMember(
            'FieldCount', 'GetProperty', $null, $record, $null)

        $fields = @()
        for ($i = 1; $i -le $count; $i++) {
            $value = $record.StringData($i)
            $fields += $(if ($null -eq $value) { '' } else { $value })
        }
        $out += ($fields -join '|')
    }

    $null = $view.Close()
    return $out
}

Write-Host "package: $Msi" -ForegroundColor Cyan
foreach ($line in Query 'SELECT `Property`, `Value` FROM `Property`') {
    $p = $line -split '\|'
    if ($p[0] -in @('ProductName', 'ProductVersion', 'Manufacturer', 'ALLUSERS', 'ProductCode',
                    'WIXUI_EXITDIALOGOPTIONALCHECKBOXTEXT', 'WIXUI_EXITDIALOGOPTIONALCHECKBOX',
                    'WixUnelevatedShellExecTarget')) {
        Write-Host ('  {0,-16} {1}' -f $p[0], $p[1])
    }
}

$files = @(Query 'SELECT FileName FROM File')
Write-Host ''
Write-Host ('files                  ' + $files.Count)
foreach ($want in @('OpenCam.exe', 'OpenCamVirtualCam.dll')) {
    # The File table stores "short|long" once a name is too long for a 8.3 slot.
    $found = @($files | Where-Object { ($_ -split '\|')[-1] -eq $want }).Count -gt 0
    Write-Host ('  {0,-22} {1}' -f $want, $(if ($found) { 'packaged' } else { 'MISSING' }))
}

Write-Host ''
Write-Host 'camera registration:' -ForegroundColor Cyan
foreach ($line in Query 'SELECT Root, `Key`, `Name`, `Value` FROM `Registry`') {
    $r = $line -split '\|'
    $root = switch ($r[0]) { '1' { 'HKCU' } '2' { 'HKLM' } default { "root $($r[0])" } }
    $name = if ($r[2]) { $r[2] } else { '(default)' }
    Write-Host ('  {0}\{1}' -f $root, $r[1])
    Write-Host ('      {0} = {1}' -f $name, $r[3])
}

Write-Host ''
Write-Host 'firewall:' -ForegroundColor Cyan
$firewall = @(Query 'SELECT `Name`, `Program`, `Profile`, `Component_`, `Description` FROM `Wix5FirewallException`')
if ($firewall.Count -eq 0) {
    Write-Host '  (none)'
} else {
    foreach ($line in $firewall) {
        $f = $line -split '\|'
        $profile = if ($f[2]) { $f[2] } else { 'all (default)' }
        Write-Host ('  "{0}" -> {1}' -f $f[0], $f[1])
        Write-Host ('      profile {0}; component {1}' -f $profile, $f[3])
        Write-Host ('      {0}' -f $f[4])
    }
}

Write-Host ''
Write-Host 'start menu:' -ForegroundColor Cyan
foreach ($line in Query 'SELECT Name, Target FROM Shortcut') {
    $s = $line -split '\|'
    Write-Host ('  shortcut "{0}" -> {1}' -f $s[0], $s[1])
}
# WiX puts folder removals in the RemoveFile table: the folder name goes in the
# file-name column with an empty short name.
# Columns come by position because FileName is a reserved word in MSI SQL:
# id, component, short name, folder, install mode.
foreach ($line in Query 'SELECT * FROM `RemoveFile`') {
    $rm = $line -split '\|'
    Write-Host ('  removes folder {0} on uninstall (component {1})' -f $rm[3], $rm[1])
}

Write-Host ''
Write-Host 'wizard and finish action:' -ForegroundColor Cyan
Write-Host ('  dialogs                ' + @(Query 'SELECT Dialog FROM Dialog').Count)
foreach ($line in Query 'SELECT `Action`, `Type`, `Source`, `Target` FROM `CustomAction` WHERE `Action` = ''LaunchApplication''') {
    $c = $line -split '\|'
    Write-Host ('  LaunchApplication: type {0}, source {1}, entry {2}' -f $c[1], $c[2], $c[3])
}
foreach ($line in Query 'SELECT `Control_`, `Event`, `Argument`, `Condition` FROM `ControlEvent` WHERE `Dialog_` = ''ExitDialog''') {
    $e = $line -split '\|'
    Write-Host ('  ExitDialog.{0}: {1} = {2}' -f $e[0], $e[1], $e[2])
    Write-Host ('      when {0}' -f $e[3])
}

Write-Host ''
Write-Host 'install location:' -ForegroundColor Cyan
foreach ($line in Query 'SELECT `Directory`, DefaultDir FROM `Directory`') {
    $d = $line -split '\|'
    if ($d[0] -in @('INSTALLFOLDER', 'OpenCamMenuFolder')) {
        Write-Host ('  {0,-22} {1}' -f $d[0], $d[1])
    }
}

Write-Host ''
Write-Host 'feature tree:' -ForegroundColor Cyan
foreach ($line in Query 'SELECT Feature, Title, Level FROM Feature') {
    $f = $line -split '\|'
    Write-Host ('  {0} "{1}" level {2}' -f $f[0], $f[1], $f[2])
}

$null = [System.Runtime.InteropServices.Marshal]::ReleaseComObject($database)
