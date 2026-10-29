# Parchea org.gradle.internal.process.ArgWriter dentro de la jar que la contiene
# en cada distribucion de Gradle instalada en ~/.gradle/wrapper/dists.
#
# Que hace: reemplaza la clase por una version que escribe los @argfiles de los
# workers con la codificacion NATIVA de la plataforma ("native.encoding") en vez
# de Charset.defaultCharset() (file.encoding). El launcher de `java` que lee esos
# @argfiles usa sun.jnu.encoding (ACP, Cp1252 en Windows); con file.encoding=UTF-8
# (default desde JEP 400) y rutas acentuadas (C:\Users\Andres\...) escritura y
# lectura no coinciden y los workers mueren con
# "ClassNotFoundException: worker.org.gradle.process.internal.worker.GradleWorkerMain".
# Referencias upstream: gradle/gradle#29303 (TODO en el codigo original) y
# gradle/gradle#30304.
#
# Idempotente: si la clase ya contiene "native.encoding", la salta.
# Hay que re-ejecutarlo tras cada actualizacion de Gradle (nueva distribucion).
# Si "jar uf" falla con "being used by another process", para los daemons antes:
# <gradle> --stop
#
# Script 100% ASCII a proposito: PowerShell 5.1 interpreta los .ps1 sin BOM con
# la codificacion de plataforma (Cp1252), asi que nunca debe llevar acentos.
param()

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$src = Join-Path $here 'ArgWriter.java'

# Herramientas del JDK: preferir el JBR de Android Studio, si existe.
$jbrBin = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr\bin'
$javac = Join-Path $jbrBin 'javac.exe'
$javap = Join-Path $jbrBin 'javap.exe'
$jar   = Join-Path $jbrBin 'jar.exe'
if (-not (Test-Path $javac)) { $javac = 'javac'; $javap = 'javap'; $jar = 'jar' }

function Invoke-Javap([string]$jarPath) {
    # Devuelve el bytecode de ArgWriter como string; $null si la clase no esta
    # en esa jar. javap escribe los errores en stderr: con 2>$null se evitan
    # ErrorRecords (con EAP=Stop lanzarian excepcion).
    $out = & $javap -c -p -cp $jarPath org.gradle.internal.process.ArgWriter 2>$null
    if ($LASTEXITCODE -ne 0) { return $null }
    return ($out | Out-String)
}

function Find-ArgWriterJar([string]$libDir) {
    # Primero la jar de base-services; si la clase no esta ahi (Gradle puede
    # moverla entre modulos al actualizar), barrer todas las gradle-*.jar.
    $cands = @(Get-ChildItem $libDir -Filter 'gradle-base-services-*.jar' -File -ErrorAction SilentlyContinue)
    foreach ($c in $cands) {
        if ($null -ne (Invoke-Javap $c.FullName)) { return $c.FullName }
    }
    $all = @(Get-ChildItem $libDir -Filter 'gradle-*.jar' -File -ErrorAction SilentlyContinue)
    foreach ($g in $all) {
        $entries = & $jar tf $g.FullName 2>$null
        if ($LASTEXITCODE -eq 0 -and ($entries -match 'org/gradle/internal/process/ArgWriter\.class')) {
            return $g.FullName
        }
    }
    return $null
}

# Localizar los dirs lib de todas las distribuciones instaladas.
$dists = Join-Path $env:USERPROFILE '.gradle\wrapper\dists'
if (-not (Test-Path $dists)) { throw "No existe $dists" }
$libDirs = @(Get-ChildItem $dists -Recurse -Directory -Filter 'lib' -ErrorAction SilentlyContinue |
             Where-Object { $_.Parent.Name -like 'gradle-*' } |
             ForEach-Object { $_.FullName })
if ($libDirs.Count -eq 0) { throw 'No se encontraron dirs lib de distribuciones de Gradle.' }

$patched = 0
$already = 0
$missing = 0
foreach ($libDir in $libDirs) {
    $target = Find-ArgWriterJar $libDir
    if ($null -eq $target) {
        "[SKIP] sin clase ArgWriter en: $libDir"
        $missing++
        continue
    }
    $bc = Invoke-Javap $target
    if ($bc -match 'native\.encoding') {
        "[OK] ya parcheada: $target"
        $already++
        continue
    }

    # Copia de seguridad una sola vez (para poder restaurar).
    $bak = $target + '.orig'
    if (-not (Test-Path $bak)) {
        Copy-Item $target $bak
        "[backup] $bak"
    }

    # Compilar el ArgWriter parcheado contra la propia jar (ahi vive ArgCollector).
    $out = Join-Path ([IO.Path]::GetTempPath()) ('argwriter-patch-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $out | Out-Null
    try {
        & $javac --release 17 -encoding UTF-8 -nowarn -cp $target -d $out $src
        if ($LASTEXITCODE -ne 0) { throw "javac fallo con codigo $LASTEXITCODE" }

        $cls = Join-Path $out 'org\gradle\internal\process\ArgWriter.class'
        if (-not (Test-Path $cls)) { throw 'javac no genero ArgWriter.class' }

        # Actualizar la jar con la clase compilada (ruta relativa con / para el jar tool).
        Push-Location $out
        try {
            & $jar uf $target 'org/gradle/internal/process/ArgWriter.class'
            if ($LASTEXITCODE -ne 0) { throw "jar uf fallo con codigo $LASTEXITCODE (daemon activo? Prueba: <gradle> --stop)" }
        } finally {
            Pop-Location
        }
    } finally {
        Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
    }

    # Verificar que la jar quedo con la logica de native.encoding.
    $after = Invoke-Javap $target
    if ($null -eq $after -or $after -notmatch 'native\.encoding') {
        throw "Verificacion fallida tras parchear: $target"
    }
    "[PATCHED] $target"
    $patched++
}
"Resultado: $patched parcheadas, $already ya estaban, $missing sin clase ArgWriter."
if ($patched -eq 0 -and $already -eq 0) { throw 'No se parcheo ninguna jar.' }
