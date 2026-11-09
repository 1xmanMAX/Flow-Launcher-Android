#!/usr/bin/env bash
# Prueba de humo del APK en un emulador Android sin aceleracion (sin /dev/kvm).
#
# Arranca un AVD x86_64 (imagen ATD de API 30) en modo headless, instala el
# APK, abre MainActivity y BuscarActivity, escribe consultas y comprueba en el
# volcado de UI que aparecen resultados (locales, calculadora y plugin "tx").
# Activa la burbuja (permiso de superposicion + interruptor de la UI) y guarda
# capturas y volcados en $SALIDA. Al terminar apaga el emulador.
#
# Uso: scripts/prueba-emulador.sh [ruta/al.apk]
# Variables: ANDROID_HOME (/opt/android-sdk), SALIDA (/tmp/flow-prueba),
#            GPU (swiftshader_indirect; la imagen ATD no admite "guest"),
#            IMAGEN (system-images;android-30;aosp_atd;x86_64), AVD (flowtest)
set -euo pipefail
raiz="$(cd "$(dirname "$0")/.." && pwd)"
apk="${1:-$raiz/android/app/build/outputs/apk/release/app-release.apk}"
sdk="${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_HOME="$sdk" ANDROID_SDK_ROOT="$sdk"
salida="${SALIDA:-/tmp/flow-prueba}"
gpu="${GPU:-swiftshader_indirect}"
imagen="${IMAGEN:-system-images;android-30;aosp_atd;x86_64}"
avd="${AVD:-flowtest}"
pkg=com.max.flowlauncher
adb="$sdk/platform-tools/adb"
mkdir -p "$salida"

paso() { printf '\n== %s\n' "$*"; }
falla() { echo "FALLO: $*" >&2; fallos=$((fallos + 1)); }
fallos=0

paso "SDK: emulador e imagen"
if [[ ! -x "$sdk/emulator/emulator" || ! -d "$sdk/${imagen//;//}" ]]; then
  "$sdk/cmdline-tools/latest/bin/sdkmanager" --install emulator "$imagen" >/dev/null < <(yes)
fi
avds="$("$sdk/emulator/emulator" -list-avds 2>/dev/null || true)"
if ! grep -qx "$avd" <<<"$avds"; then
  echo no | "$sdk/cmdline-tools/latest/bin/avdmanager" create avd -n "$avd" -k "$imagen" -d pixel_4 --force >/dev/null
  cfg="$HOME/.android/avd/$avd.avd/config.ini"
  sed -i -e 's/^hw.ramSize=.*/hw.ramSize=3072/' -e 's/^hw.cpu.ncore=.*/hw.cpu.ncore=4/' \
         -e 's/^disk.dataPartition.size=.*/disk.dataPartition.size=2G/' "$cfg"
fi

paso "Arranque headless (sin KVM: ~5 min)"
"$sdk/emulator/emulator" -avd "$avd" -no-window -no-audio -no-boot-anim -accel off \
  -gpu "$gpu" -no-snapshot -no-metrics >"$salida/emulator.log" 2>&1 &
trap '"$adb" logcat -d >"$salida/logcat-salida.txt" 2>/dev/null; "$adb" emu kill >/dev/null 2>&1 || true' EXIT
t0=$(date +%s)
until [[ "$("$adb" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; do
  (( $(date +%s) - t0 > 1500 )) && { echo "El emulador no arranco en 25 min"; exit 1; }
  sleep 10
done
echo "Arrancado en $(( $(date +%s) - t0 )) s"
"$adb" root >/dev/null; sleep 3; "$adb" wait-for-device
"$adb" shell svc power stayon true
"$adb" shell settings put system screen_off_timeout 2147483647
"$adb" shell input keyevent KEYCODE_WAKEUP; "$adb" shell wm dismiss-keyguard
"$adb" shell dumpsys power | grep -m1 mWakefulness= || true

paso "Instalacion"
"$adb" install -r "$apk"
# Datos limpios: que no quede nada de una prueba anterior (la burbuja activa...).
"$adb" shell pm clear com.max.flowlauncher >/dev/null
# Sin el proveedor de prueba de otra vuelta (se le cederia la palabra p).
"$adb" uninstall com.max.flowlauncher.prueba >/dev/null 2>&1 || true
# Un archivo para buscar con «c», y permiso de acceso a todos los archivos.
"$adb" shell 'mkdir -p /sdcard/Download/Universidad && echo hola > "/sdcard/Download/Universidad/Tesis final.pdf"'
"$adb" shell appops set --uid com.max.flowlauncher MANAGE_EXTERNAL_STORAGE allow >/dev/null 2>&1 || true
# Un APK para instalar desde «c» (el de la app de prueba) y su permiso.
prueba_apk_c="$raiz/android/proveedor-prueba/build/outputs/apk/debug/proveedor-prueba-debug.apk"
[[ -f "$prueba_apk_c" ]] && "$adb" push "$prueba_apk_c" /sdcard/Download/AppDePrueba.apk >/dev/null
"$adb" shell appops set com.max.flowlauncher REQUEST_INSTALL_PACKAGES allow >/dev/null 2>&1 || true
# Dos con el mismo nombre y distinta fecha: el nuevo tiene que salir primero.
"$adb" shell 'echo a > "/sdcard/Download/Informe viejo.pdf"; touch -d 2020-01-01T10:00:00 "/sdcard/Download/Informe viejo.pdf"; sleep 2; echo b > "/sdcard/Download/informe nuevo.pdf"; touch -d 2019-06-01T10:00:00 "/sdcard/Download/informe nuevo.pdf"'
"$adb" logcat -c
libdir="$("$adb" shell dumpsys package $pkg | sed -n 's/.*legacyNativeLibraryDir=//p' | tr -d '\r' | head -1)"
"$adb" shell ls -l "$libdir/x86_64/" | tee "$salida/nativelibs.txt"
grep -q -- '-rwx.*libflow-texto.so' "$salida/nativelibs.txt" || falla "libflow-texto.so no es ejecutable"

volcar() {  # $1 = nombre; deja $salida/$1.xml, $1.png y el texto visible en $1.txt
  "$adb" shell uiautomator dump /sdcard/ui.xml >/dev/null
  "$adb" pull /sdcard/ui.xml "$salida/$1.xml" >/dev/null
  grep -o ' text="[^"]*"' "$salida/$1.xml" | sed 's/^ text="//; s/"$//' | grep -v '^$' >"$salida/$1.txt" || true
  "$adb" exec-out screencap -p >"$salida/$1.png"
}
espera() {  # $1 = nombre, $2 = texto esperado
  grep -qF -- "$2" "$salida/$1.txt" && echo "OK  $1: «$2»" || falla "$1: no aparece «$2»"
}
tocar() {  # $1 = volcado donde buscar, $2 = texto, $3 = "largo" para mantener pulsado
  local b
  b="$(grep -o "text=\"$2\"[^>]*bounds=\"[^\"]*\"" "$salida/$1.xml" | head -1 | grep -o 'bounds="[^"]*"' | tr -dc '0-9,[]' )"
  [[ -z "$b" ]] && { falla "$1: no se puede tocar «$2»"; return; }
  local n=(${b//[^0-9]/ })
  local x=$(( (n[0] + n[2]) / 2 )) y=$(( (n[1] + n[3]) / 2 ))
  if [[ "${3:-}" == largo ]]; then "$adb" shell input swipe $x $y $x $y 900; else "$adb" shell input tap $x $y; fi
  sleep 4
}
no_espera() {  # $1 = nombre, $2 = texto que NO debe salir
  grep -qF -- "$2" "$salida/$1.txt" && falla "$1: no deberia salir «$2»" || echo "OK  $1: sin «$2»"
}

paso "MainActivity (arranque en frio)"
"$adb" shell am start -W -n $pkg/.MainActivity | tee "$salida/start-main.txt"
sleep 4; volcar main
espera main "Flow Buscador"
# La lista de plugins queda abajo: bajar hasta el final.
for _ in 1 2 3; do "$adb" shell input swipe 500 1600 500 300 200; done; sleep 2
volcar main-abajo; espera main-abajo "Listo"

paso "BuscarActivity (arranque en frio) y consultas"
"$adb" shell am force-stop $pkg
"$adb" shell am start -W -n $pkg/.BuscarActivity | tee "$salida/start-buscar.txt"
sleep 3
consulta() {  # $1 = nombre, $2 = texto (espacios como %s), $3 = resultado esperado
  "$adb" shell input keyevent KEYCODE_MOVE_END
  "$adb" shell input keyevent --longpress KEYCODE_DEL >/dev/null 2>&1; for _ in $(seq 1 60); do echo KEYCODE_DEL; done | xargs "$adb" shell input keyevent
  "$adb" shell input text "$2"; sleep 5
  volcar "$1"; espera "$1" "$3"
}
consulta buscar-set "set" "Ajustes del sistema"
consulta buscar-calc "2+2" "2+2 — toca para copiar"
consulta buscar-tx "tx%shola" "HOLA"
# Comandos con otras apps (sin plugins).
consulta cmd-timer "timer%s10m" "Temporizador de 10 min"
consulta cmd-wa "wa%s987654321%shola" "Chat de WhatsApp con 987654321"
espera cmd-wa "Enviar «987654321 hola» por WhatsApp"
consulta cmd-cal "cal%sreunion%smanana%s10:30" "Evento: reunion"
consulta cmd-sugerir "tim" "Temporizador  ·  timer"
consulta buscar-js "cv%s10%skm%sa%smi" "6.213712"
consulta buscar-pixpin "p%st%scomprar%span" "Apuntar «comprar pan» como tarea"
consulta buscar-archivos "c%stesis" "Tesis final.pdf"
# Mantener pulsado el archivo: el menu con «Compartir», y compartir abre la lista de apps.
tocar buscar-archivos "Tesis final.pdf" largo; volcar menu-archivo
espera menu-archivo "Compartir"; espera menu-archivo "Copiar la ruta"
tocar menu-archivo "Compartir"; volcar compartir
espera compartir "Tesis final.pdf"
"$adb" shell input keyevent KEYCODE_BACK; sleep 2
"$adb" shell am start -W -n $pkg/.BuscarActivity >/dev/null; sleep 4
consulta buscar-fecha "c%sinforme" "informe nuevo.pdf"
n1=$(grep -n "informe nuevo.pdf" "$salida/buscar-fecha.txt" | head -1 | cut -d: -f1)
n2=$(grep -n "Informe viejo.pdf" "$salida/buscar-fecha.txt" | head -1 | cut -d: -f1)
[[ -n "$n1" && -n "$n2" && "$n1" -lt "$n2" ]] && echo "OK  orden por fecha: lo nuevo arriba" || falla "orden por fecha ($n1, $n2)"
# Tocar un APK abre el instalador del sistema.
consulta buscar-apk "c%sappdeprueba" "AppDePrueba.apk"
tocar buscar-apk "AppDePrueba.apk"; volcar instalador
grep -qiE 'packageinstaller' "$salida/instalador.xml" && echo "OK  instalador: se abre" || falla "instalador: no se abre"
"$adb" shell input keyevent KEYCODE_BACK; sleep 2
"$adb" shell am start -W -n $pkg/.BuscarActivity >/dev/null; sleep 4
espera buscar-tx "aG9sYQ=="
"$adb" shell "ps -A -o PID,PPID,NAME | grep -E 'flowlauncher|flow-texto'" | tee "$salida/procesos.txt"
grep -q libflow-texto.so "$salida/procesos.txt" || falla "no hay proceso del plugin"

paso "Proveedor de busqueda (app de prueba con la palabra p)"
prueba_apk="${PROVEEDOR:-$raiz/android/proveedor-prueba/build/outputs/apk/debug/proveedor-prueba-debug.apk}"
if [[ -f "$prueba_apk" ]]; then
  "$adb" install -r "$prueba_apk" >/dev/null
  # El buscador lee los proveedores al arrancar el proceso.
  "$adb" shell am force-stop $pkg; sleep 1
  "$adb" shell am start -W -n $pkg/.BuscarActivity >/dev/null; sleep 6
  consulta prov-tesis "p%stes" "Tesis final.pdf"
  espera prov-tesis "Tesis borrador.docx"
  # Lo generico del plugin de reserva ya no sale: el proveedor manda.
  no_espera prov-tesis "en PixPin"
  consulta prov-tarea "p%st%span" "Apuntar «pan» en Inbox"
  no_espera prov-tarea "como tarea"
  "$adb" shell am force-stop $pkg
else
  echo "(sin $prueba_apk: se salta)"
fi

paso "Burbuja"
"$adb" shell appops set $pkg SYSTEM_ALERT_WINDOW allow
"$adb" shell input keyevent KEYCODE_BACK
"$adb" shell am start -W -n $pkg/.MainActivity >/dev/null; sleep 3
volcar main-antes
bounds="$(grep -o '<node [^>]*text="Burbuja flotante"[^>]*>' "$salida/main-antes.xml" | grep -o 'bounds="[^"]*"' | head -1 || true)"
read -r x1 y1 x2 y2 <<<"$(echo "$bounds" | grep -oE '[0-9]+' | tr '\n' ' ')"
"$adb" shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 )); sleep 6
servicios="$("$adb" shell dumpsys activity services $pkg)"
grep -q 'isForeground=true' <<<"$servicios" && echo "OK  BurbujaService en primer plano" || falla "BurbujaService no esta en primer plano"
ventana=""
for _ in 1 2 3 4 5 6; do  # la ventana de la burbuja puede tardar en aparecer sin KVM
  ventanas="$("$adb" shell dumpsys window windows | tr -d '\r')"
  ventana="$(grep -A4 "u0 $pkg}:" <<<"$ventanas" | grep 'APPLICATION_OVERLAY' | grep -oE '\([0-9]+,[0-9]+\)\([0-9]+x[0-9]+\)' | head -1 || true)"
  [[ -n "$ventana" ]] && break
  sleep 5
done
echo "Ventana superpuesta: ${ventana:-ninguna}"
if [[ -n "$ventana" ]]; then
  read -r bx by bw bh <<<"$(echo "$ventana" | grep -oE '[0-9]+' | tr '\n' ' ')"
  "$adb" shell input keyevent KEYCODE_HOME; sleep 3
  "$adb" shell input tap $(( bx + bw / 2 )) $(( by + bh / 2 )); sleep 6
  "$adb" shell input text "tx%shola"; sleep 5
  volcar burbuja-tx; espera burbuja-tx "HOLA"
else
  falla "no hay ventana APPLICATION_OVERLAY"
fi

paso "Logcat"
"$adb" logcat -d >"$salida/logcat.txt"
if grep -E "FATAL EXCEPTION|UnsatisfiedLinkError|Fatal signal|ANR in $pkg" "$salida/logcat.txt" | grep -C0 -E "$pkg|FATAL|Unsatisfied"; then
  falla "crash en logcat (ver $salida/logcat.txt)"
fi
"$adb" shell ls /data/tombstones/ 2>/dev/null | tee "$salida/tombstones.txt" || true

paso "Resultado: $fallos fallo(s). Ficheros en $salida"
exit $(( fallos > 0 ))
