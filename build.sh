#!/usr/bin/env bash
# Compila o bundle e empacota o JAR (macOS, Linux, Git Bash no Windows).
#
# Nao usa PDE nem target platform: o classpath sai dos bundles do Eclipse ja
# instalados. O compilador e o ecj do proprio Eclipse, nao o javac: os bundles
# do ADT sao classfiles Java 21 e um javac mais antigo os recusa com
# "class file has wrong version 65.0".
#
#   ./build.sh                       gera build/<bundle>.jar
#   ./build.sh --install             gera e copia para o dropins do Eclipse
#   ECLIPSE_HOME=/opt/eclipse ./build.sh   quando a deteccao automatica falhar

set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
bundle_name="io.github.felipemalmeida.adt.mcp.write_0.1.0.jar"
instalar="nao"
[ "${1:-}" = "--install" ] && instalar="sim"

# --- Onde esta o Eclipse -----------------------------------------------------

if [ -z "${ECLIPSE_HOME:-}" ]; then
  for candidato in \
    "$HOME/eclipse"/*/eclipse "$HOME/eclipse" \
    "/Applications/Eclipse.app/Contents/Eclipse" \
    "/opt/eclipse" "/usr/lib/eclipse"; do
    if [ -d "$candidato/dropins" ]; then ECLIPSE_HOME="$candidato"; break; fi
  done
fi
if [ -z "${ECLIPSE_HOME:-}" ] || [ ! -d "$ECLIPSE_HOME" ]; then
  echo "Eclipse nao encontrado. Defina ECLIPSE_HOME=<caminho>." >&2
  exit 1
fi

# Os JARs podem estar em <eclipse>/plugins ou no bundle pool compartilhado do p2.
pools=()
[ -d "$ECLIPSE_HOME/plugins" ] && pools+=("$ECLIPSE_HOME/plugins")
[ -d "$HOME/.p2/pool/plugins" ] && pools+=("$HOME/.p2/pool/plugins")
[ ${#pools[@]} -eq 0 ] && { echo "Nenhuma pasta de plugins a partir de $ECLIPSE_HOME" >&2; exit 1; }

# Ha varias versoes de cada bundle no pool; pegamos sempre a maior.
resolve_bundle() {
  local prefixo="$1" opcional="${2:-}" achado
  achado="$(ls -1d "${pools[@]}/$prefixo"* 2>/dev/null | sort -V | tail -1 || true)"
  if [ -z "$achado" ]; then
    [ "$opcional" = "opcional" ] && return 0
    echo "Bundle $prefixo nao encontrado em: ${pools[*]}" >&2
    exit 1
  fi
  printf '%s' "$achado"
}

# --- Com o que compilar ------------------------------------------------------

ecj="$(resolve_bundle 'org.eclipse.jdt.core.compiler.batch_')"

java_bin="java"
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  java_bin="$JAVA_HOME/bin/java"
elif ! command -v java >/dev/null 2>&1; then
  jre="$(resolve_bundle 'org.eclipse.justj.openjdk' opcional)"
  [ -n "$jre" ] && java_bin="$jre/jre/bin/java"
fi

# No Git Bash o javac/ecj precisa de caminho no formato do Windows.
to_native() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi }
sep=":"; command -v cygpath >/dev/null 2>&1 && sep=";"

classpath=""
for prefixo in \
  'com.sap.adt.mcp.core_' 'com.sap.adt.communication_' \
  'org.eclipse.equinox.common_' 'org.eclipse.core.runtime_' 'org.eclipse.core.jobs_'; do
  classpath="${classpath}${sep}$(to_native "$(resolve_bundle "$prefixo")")"
done
classpath="${classpath#$sep}"

# --- Compilar ----------------------------------------------------------------

rm -rf "$root/bin"
mkdir -p "$root/bin" "$root/build"

mapfile -t sources < <(find "$root/src" -name '*.java')
echo "Compilando ${#sources[@]} fontes com ecj..."

native_sources=()
for fonte in "${sources[@]}"; do native_sources+=("$(to_native "$fonte")"); done

"$java_bin" -jar "$(to_native "$ecj")" -source 17 -target 17 -encoding UTF-8 -nowarn \
  -cp "$classpath" -d "$(to_native "$root/bin")" "${native_sources[@]}"

# --- Empacotar ---------------------------------------------------------------

cp "$root/plugin.xml" "$root/bin/"
mkdir -p "$root/bin/META-INF"
cp "$root/META-INF/MANIFEST.MF" "$root/bin/META-INF/MANIFEST.MF"

alvo="$root/build/$bundle_name"
rm -f "$alvo"
echo "Empacotando $alvo..."

if command -v jar >/dev/null 2>&1; then
  (cd "$root/bin" && jar --create --file "$alvo" --manifest META-INF/MANIFEST.MF .)
elif command -v zip >/dev/null 2>&1; then
  (cd "$root/bin" && zip -qr "$alvo" .)
elif command -v python3 >/dev/null 2>&1; then
  python3 - "$root/bin" "$alvo" <<'PY'
import os, sys, zipfile
origem, alvo = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(alvo, "w", zipfile.ZIP_DEFLATED) as jar:
    for pasta, _, arquivos in os.walk(origem):
        for arquivo in arquivos:
            caminho = os.path.join(pasta, arquivo)
            jar.write(caminho, os.path.relpath(caminho, origem).replace(os.sep, "/"))
PY
else
  echo "Preciso de jar, zip ou python3 para empacotar." >&2
  exit 1
fi

if [ "$instalar" = "sim" ]; then
  cp "$alvo" "$ECLIPSE_HOME/dropins/"
  echo "Instalado em $ECLIPSE_HOME/dropins - reinicie o Eclipse para o bundle ser lido."
else
  echo "Pronto. Para instalar: ./build.sh --install"
fi
