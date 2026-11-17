# Creates only this application's inbound LAN rules. Run by the explicit UI button.
param(
    [Parameter(Mandatory=$true)][string]$ProgramPath,
    [switch]$IncludePublic
)
$ErrorActionPreference = 'Stop'

# A short, stable hash of the program path keeps rule names unique per installed copy of the app.
function Get-ProgramHash([string]$Path) {
    $hasher = [System.Security.Cryptography.SHA256]::Create()
    try {
        $pathBytes = [Text.Encoding]::UTF8.GetBytes($Path.ToLowerInvariant())
        $hashText = [BitConverter]::ToString($hasher.ComputeHash($pathBytes))
        return $hashText.Replace('-', '').Substring(0, 12)
    } finally {
        $hasher.Dispose()
    }
}

try {
    $resolvedProgram = (Resolve-Path -LiteralPath $ProgramPath).Path
    $ruleHash = Get-ProgramHash $resolvedProgram
    $profiles = @('Private', 'Domain')
    if ($IncludePublic) { $profiles += 'Public' }

    foreach ($rule in @(@('TCP', 8080), @('UDP', 8888))) {
        $protocol = $rule[0]
        $port = $rule[1]
        $ruleName = "P3kGamepadLAN-$ruleHash-$protocol"
        $existing = Get-NetFirewallRule -Name $ruleName -ErrorAction SilentlyContinue
        if ($existing) { Remove-NetFirewallRule -Name $ruleName }

        $newRule = @{
            Name          = $ruleName
            DisplayName   = "P3k Gamepad LAN $protocol"
            Group         = 'P3k Gamepad over LAN'
            Direction     = 'Inbound'
            Action        = 'Allow'
            Program       = $resolvedProgram
            Protocol      = $protocol
            LocalPort     = $port
            RemoteAddress = 'LocalSubnet'
            Profile       = $profiles
        }
        New-NetFirewallRule @newRule | Out-Null
    }
    exit 0
} catch {
    Write-Error $_
    exit 1
}
