$bytes = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$hex = [BitConverter]::ToString($bytes).Replace('-', '')
$hex