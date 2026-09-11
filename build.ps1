# Compila o bundle e empacota o JAR.
#
# Nao usa PDE nem target platform: o classpath e montado direto a partir dos
# bundles do Eclipse ja instalados na maquina.
#
# O compilador e o ecj (batch compiler do proprio Eclipse), nao o javac: os
# bundles do ADT sao classfiles Java 21 e um javac mais antigo os recusa com
# "class file has wrong version 65.0". O ecj vem junto com o Eclipse e le
# qualquer versao.
#
#   .\build.ps1                          gera build\<bundle>.jar
#   .\build.ps1 -Install                 gera e copia para o dropins do Eclipse
#   .\build.ps1 -EclipseHome C:\eclipse  quando a deteccao automatica falhar

param(
    [string]$EclipseHome,
    [switch]$Install
)

$ErrorActionPreference = 'Stop'

$root       = $PSScriptRoot
$bundleName = 'io.github.felipemalmeida.adt.mcp.write_0.1.0.jar'

# --- Onde esta o Eclipse -----------------------------------------------------

if (-not $EclipseHome) { $EclipseHome = $env:ECLIPSE_HOME }
if (-not $EclipseHome) {
    $candidatos = @(
        (Join-Path $env:USERPROFILE 'eclipse\*\eclipse'),
        (Join-Path $env:USERPROFILE 'eclipse'),
        'C:\Program Files\Eclipse Foundation\*',
        'C:\Program Files\eclipse',
        'C:\eclipse'
    )
    foreach ($padrao in $candidatos) {
        $achado = Get-ChildItem -Path $padrao -Directory -ErrorAction SilentlyContinue |
                  Where-Object { Test-Path (Join-Path $_.FullName 'dropins') } |
                  Select-Object -First 1
        if ($achado) { $EclipseHome = $achado.FullName; break }
    }
}
if (-not $EclipseHome -or -not (Test-Path $EclipseHome)) {
    throw 'Eclipse nao encontrado. Passe -EclipseHome <caminho> ou defina $env:ECLIPSE_HOME.'
}

# Os JARs podem estar em <eclipse>\plugins ou no bundle pool compartilhado do p2.
$poolDirs = @(
    (Join-Path $EclipseHome 'plugins'),
    (Join-Path $env:USERPROFILE '.p2\pool\plugins')
) | Where-Object { Test-Path $_ }

if (-not $poolDirs) { throw "Nenhuma pasta de plugins encontrada a partir de $EclipseHome" }

function Resolve-Bundle([string]$prefix, [switch]$Opcional) {
    # Ha varias versoes de cada bundle no pool (o Eclipse guarda as antigas);
    # pegamos sempre a maior.
    $match = Get-ChildItem -Path $poolDirs -Filter "$prefix*" -ErrorAction SilentlyContinue |
             Sort-Object Name -Descending |
             Select-Object -First 1
    if (-not $match) {
        if ($Opcional) { return $null }
        throw "Bundle $prefix nao encontrado em: $($poolDirs -join ', ')"
    }
    return $match.FullName
}

# --- Com o que compilar ------------------------------------------------------

$ecj = Resolve-Bundle 'org.eclipse.jdt.core.compiler.batch_'

$java = 'java'
if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $java = Join-Path $env:JAVA_HOME 'bin\java.exe'
} elseif (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    # Sem Java no PATH, usa a JRE que o proprio Eclipse embute.
    $jre = Resolve-Bundle 'org.eclipse.justj.openjdk' -Opcional
    if ($jre) { $java = Join-Path $jre 'jre\bin\java.exe' }
}
if ($java -ne 'java' -and -not (Test-Path $java)) { throw "Java nao encontrado em $java" }

$classpath = @(
    Resolve-Bundle 'com.sap.adt.mcp.core_'
    Resolve-Bundle 'com.sap.adt.communication_'
    Resolve-Bundle 'org.eclipse.equinox.common_'
    Resolve-Bundle 'org.eclipse.core.runtime_'
    Resolve-Bundle 'org.eclipse.core.jobs_'
) -join ';'

# --- Compilar ----------------------------------------------------------------

$bin   = Join-Path $root 'bin'
$build = Join-Path $root 'build'
Remove-Item $bin -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $bin   -Force | Out-Null
New-Item -ItemType Directory -Path $build -Force | Out-Null

$sources = Get-ChildItem -Path (Join-Path $root 'src') -Filter '*.java' -Recurse |
           ForEach-Object { $_.FullName }

Write-Host "Compilando $($sources.Count) fontes com ecj..."
& $java -jar $ecj -source 17 -target 17 -encoding UTF-8 -nowarn -cp $classpath -d $bin $sources
if ($LASTEXITCODE -ne 0) { throw "ecj falhou com codigo $LASTEXITCODE" }

# --- Empacotar ---------------------------------------------------------------

Copy-Item (Join-Path $root 'plugin.xml') $bin
New-Item -ItemType Directory -Path (Join-Path $bin 'META-INF') -Force | Out-Null
Copy-Item (Join-Path $root 'META-INF\MANIFEST.MF') (Join-Path $bin 'META-INF\MANIFEST.MF')

$target = Join-Path $build $bundleName
Remove-Item $target -Force -ErrorAction SilentlyContinue
Write-Host "Empacotando $target..."

# Um JAR e um ZIP, mas o separador DENTRO do zip tem de ser "/". O
# Compress-Archive do Windows PowerShell grava "META-INF\MANIFEST.MF" com barra
# invertida e o Equinox nao acha o manifesto — bundle nao resolve, sem erro
# visivel. Por isso as entradas sao criadas uma a uma, com o nome normalizado.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($target, 'Create')
try {
    foreach ($arquivo in Get-ChildItem -Path $bin -Recurse -File) {
        $relativo = $arquivo.FullName.Substring($bin.Length + 1).Replace('\', '/')
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $arquivo.FullName, $relativo) | Out-Null
    }
} finally {
    $zip.Dispose()
}

if ($Install) {
    if (Get-Process -Name eclipse -ErrorAction SilentlyContinue) {
        Write-Warning 'Eclipse aberto: o dropins so e lido no start, entao reinicie depois.'
    }
    $dropins = Join-Path $EclipseHome 'dropins'
    Copy-Item $target $dropins -Force
    Write-Host "Instalado em $dropins - reinicie o Eclipse para o bundle ser lido."
} else {
    Write-Host "Pronto. Para instalar: .\build.ps1 -Install"
}
