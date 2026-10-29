# Extremo TCP de prueba para el enlace LAN de AS GBA Emulator.
#
# Sustituye al segundo telefono cuando solo hay un dispositivo: habla el mismo
# protocolo que el nativo (trama [tipo u8][longitud u16][payload]) para que el
# enlace de la app se complete sin el otro jugador.
#
# Uso:
#   -HostRole child   la app se une: este script crea la sala y espera
#   -HostRole parent  la app crea la sala: este script se une a -AppHost
param(
    [ValidateSet('child', 'parent')]
    [string]$HostRole = 'child',
    [string]$AppHost = '127.0.0.1',
    [int]$Port = 17017
)

$ErrorActionPreference = 'Stop'

function Write-U32([byte[]]$Buffer, [int]$Offset, [uint32]$Value) {
    $Buffer[$Offset]     = [byte]($Value -band 0xFF)
    $Buffer[$Offset + 1] = [byte](($Value -shr 8) -band 0xFF)
    $Buffer[$Offset + 2] = [byte](($Value -shr 16) -band 0xFF)
    $Buffer[$Offset + 3] = [byte](($Value -shr 24) -band 0xFF)
}

function Read-U32([byte[]]$Buffer, [int]$Offset) {
    return [uint32]$Buffer[$Offset] -bor ([uint32]$Buffer[$Offset + 1] -shl 8) -bor
           ([uint32]$Buffer[$Offset + 2] -shl 16) -bor ([uint32]$Buffer[$Offset + 3] -shl 24)
}

function Write-Frame($Stream, [byte]$Type, [byte[]]$Payload) {
    $length = $Payload.Length
    $frame = New-Object byte[] (3 + $length)
    $frame[0] = $Type
    $frame[1] = [byte]($length -band 0xFF)
    $frame[2] = [byte](($length -shr 8) -band 0xFF)
    [Array]::Copy($Payload, 0, $frame, 3, $length)
    $Stream.Write($frame, 0, $frame.Length)
    $Stream.Flush()
}

function Read-Frame($Stream) {
    $header = New-Object byte[] 3
    $read = 0
    while ($read -lt 3) {
        $got = $Stream.Read($header, $read, 3 - $read)
        if ($got -le 0) { return $null }
        $read += $got
    }
    $length = [int]$header[1] -bor ([int]$header[2] -shl 8)
    $payload = New-Object byte[] $length
    $read = 0
    while ($read -lt $length) {
        $got = $Stream.Read($payload, $read, $length - $read)
        if ($got -le 0) { return $null }
        $read += $got
    }
    return [pscustomobject]@{ Type = $header[0]; Payload = $payload }
}

# Papel que ANUNCIA este extremo, que es el contrario al de la app:
#   -HostRole parent -> la app creo la sala -> este script es el hijo (2)
#   -HostRole child  -> la app se une        -> este script es el padre (1)
$role = if ($HostRole -eq 'parent') { 2 } else { 1 }

if ($HostRole -eq 'child') {
    # La app es el padre: este script se une como hijo.
    Write-Host "Esperando a la app en el puerto $Port..."
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Any, $Port)
    $listener.Start()
    $client = $listener.AcceptTcpClient()
    $listener.Stop()
    Write-Host "App conectada desde $($client.Client.RemoteEndPoint)"
} else {
    # La app creo la sala: este script se une a ella como invitado.
    Write-Host "Conectando a la sala de la app en $AppHost`:$Port..."
    $client = $null
    $deadline = (Get-Date).AddSeconds(30)
    while ($null -eq $client -and (Get-Date) -lt $deadline) {
        try {
            $client = [System.Net.Sockets.TcpClient]::new()
            $connect = $client.BeginConnect($AppHost, $Port, $null, $null)
            if (-not $connect.AsyncWaitHandle.WaitOne(2000)) {
                $client.Close()
                $client = $null
                continue
            }
            $client.EndConnect($connect)
        } catch {
            if ($null -ne $client) { $client.Close() }
            $client = $null
            Start-Sleep -Milliseconds 300
        }
    }
    if ($null -eq $client) { throw "No se pudo unir a la sala de la app" }
    Write-Host "Unido a la sala de la app ($($client.Client.RemoteEndPoint))"
}

$stream = $client.GetStream()
$stream.ReadTimeout = 10000

# Saludo: "ASGB" | version 1 | rol
$hello = New-Object byte[] 6
Write-U32 $hello 0 0x42475341
$hello[4] = 1
$hello[5] = [byte]$role
Write-Frame $stream 0 $hello
Write-Host "Saludo enviado (rol $role)"

$deadline = (Get-Date).AddSeconds(120)
while ((Get-Date) -lt $deadline) {
    try {
        $frame = Read-Frame $stream
    } catch {
        # Sin tramas durante el tiempo de espera no es un fallo: el par puede
        # estar solo con su ROM corriendo y sin transferir. Se sigue escuchando.
        continue
    }
    if ($null -eq $frame) { Write-Host "La app cerro la conexion"; break }
    switch ($frame.Type) {
        0 { Write-Host "Saludo de la app: version $($frame.Payload[4]), rol $($frame.Payload[5])" }
        1 {
            $state = Read-U32 $frame.Payload 0
            $rcnt = ($state -shr 16) -band 0xFFFF
            $siocnt = $state -band 0xFFFF
            Write-Host ("Estado de linea de la app: RCNT=0x{0:X4} SIOCNT=0x{1:X4}" -f $rcnt, $siocnt)
        }
        2 {
            $bits = $frame.Payload[0]
            $sequence = $frame.Payload[1]
            $word = Read-U32 $frame.Payload 2
            Write-Host ("Peticion de transferencia: {0} bits, orden {1}, palabra 0x{2:X}" -f $bits, $sequence, $word)
            # Respuesta: banderas | orden | palabra. En multi la trama son 18
            # bits (arranque 0, 16 de datos, parada 1): la palabra se envia
            # como los 16 de datos, y los dos extremos de los bits de marco los
            # pone el nativo al validar.
            $response = New-Object byte[] 6
            $response[0] = 1
            $response[1] = $sequence
            $back = if ($bits -le 16) { $word } else { 0xBEEF }
            Write-U32 $response 2 $back
            Write-Frame $stream 3 $response
            Write-Host ("Respuesta enviada: 0x{0:X}" -f $back)
        }
        3 { Write-Host "Respuesta inesperada desde la app" }
        default { Write-Host "Trama desconocida: $($frame.Type)" }
    }
}

$client.Close()
