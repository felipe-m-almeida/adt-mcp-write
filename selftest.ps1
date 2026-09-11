# Roda o autoteste das partes que nao falam com o SAP (parser de argumentos,
# travas, leitura das respostas XML). Nao precisa do Eclipse aberto nem de
# conexao com nenhum sistema. Exige .\build.ps1 antes.

param(
    [string]$EclipseHome
)

$ErrorActionPreference = 'Stop'

$root = $PSScriptRoot
$bin  = Join-Path $root 'bin'
if (-not (Test-Path $bin)) { throw 'Rode .\build.ps1 antes do selftest.' }

if (-not $EclipseHome) { $EclipseHome = $env:ECLIPSE_HOME }
if (-not $EclipseHome) {
    $EclipseHome = (Get-ChildItem -Path (Join-Path $env:USERPROFILE 'eclipse\*\eclipse') -Directory -ErrorAction SilentlyContinue |
                    Select-Object -First 1).FullName
}
if (-not $EclipseHome) { throw 'Eclipse nao encontrado. Passe -EclipseHome <caminho>.' }

$poolDirs = @(
    (Join-Path $EclipseHome 'plugins'),
    (Join-Path $env:USERPROFILE '.p2\pool\plugins')
) | Where-Object { Test-Path $_ }

function Resolve-Bundle([string]$prefix) {
    $match = Get-ChildItem -Path $poolDirs -Filter "$prefix*" -ErrorAction SilentlyContinue |
             Sort-Object Name -Descending |
             Select-Object -First 1
    if (-not $match) { throw "Bundle $prefix nao encontrado" }
    return $match.FullName
}

$ecj  = Resolve-Bundle 'org.eclipse.jdt.core.compiler.batch_'
$java = 'java'
if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $java = Join-Path $env:JAVA_HOME 'bin\java.exe'
} elseif (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    $java = Join-Path (Resolve-Bundle 'org.eclipse.justj.openjdk') 'jre\bin\java.exe'
}

$classpath = @(
    Resolve-Bundle 'com.sap.adt.mcp.core_'
    Resolve-Bundle 'com.sap.adt.communication_'
    Resolve-Bundle 'org.eclipse.equinox.common_'
    Resolve-Bundle 'org.eclipse.core.runtime_'
    Resolve-Bundle 'org.eclipse.core.jobs_'
) -join ';'

$testbin = Join-Path $root 'testbin'
Remove-Item $testbin -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $testbin -Force | Out-Null

$sources = Get-ChildItem -Path (Join-Path $root 'test') -Filter '*.java' -Recurse |
           ForEach-Object { $_.FullName }

& $java -jar $ecj -source 17 -target 17 -encoding UTF-8 -nowarn -cp "$classpath;$bin" -d $testbin $sources
if ($LASTEXITCODE -ne 0) { throw "ecj falhou com codigo $LASTEXITCODE" }

& $java -cp "$testbin;$bin;$classpath" io.github.felipemalmeida.adt.mcp.write.SelfTest
if ($LASTEXITCODE -ne 0) { throw 'Autoteste falhou.' }
