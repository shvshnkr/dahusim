#!/bin/sh
# long-run.sh — отслеживаемый фоновый процесс (docs/SCRIPTING_POLICY.md).
#
# Зачем: долгая команда НЕ выполняется «в тишине с мигающим курсором». Она
# запускается detached-процессом (PowerShell Start-Process -PassThru — переживает
# завершение terminal-вызова), лог пишется в artifacts/<name>.log, статус и
# хвост лога видны в любой момент.
#
# Портирован из IsaevAudio/.kilo/instructions/long-running-commands.md
# (C:\Users\user\projects\IsaevAudio\scripts\long-run.sh).
#
# Окружение: bash в opencode/zed = WSL (не Git Bash!) — пути C:\ внутри bash не
# работают, Windows-программы вызываются по /mnt/c/... Скрипт сам определяет
# WSL (wslpath) или Git Bash/MSYS (cygpath) и использует нужные пути;
# CRLF-окончания (write_file/core.autocrlf) чинит сам при старте.
#
# Usage (из PowerShell: bash buildScript/long-run.sh ...):
#   buildScript/long-run.sh start <name> -- <command...>   # запустить (log+pid в artifacts/)
#   buildScript/long-run.sh status <name>                  # жив? сколько идёт + хвост лога
#   buildScript/long-run.sh log <name> [N]                 # хвост лога (по умолчанию 20 строк)
#   buildScript/long-run.sh stop <name>                    # taskkill /F /T по pid-файлу
#   buildScript/long-run.sh list                           # все запущенные (по artifacts/*.pid)
#
# Каталог логов: $LR_ART (по умолчанию <root>/artifacts).
#
# Команда выполняется через powershell -Command: одинарные кавычки допустимы,
# сложные конструкции (> < | &) — заверните в sh-скрипт: `start x -- bash buildScript/x.sh`.

# --- CRLF self-heal: write_file/core.autocrlf дают CRLF, /bin/sh его не парсит.
# Копия без \r запускается из $TMPDIR, ROOT передаётся через env.
_cr=$(printf '\r')
if grep -q "$_cr" "$0" 2>/dev/null; then
  _tmp=$(mktemp) || exit 1
  sed 's/\r$//' "$0" > "$_tmp" || exit 1
  chmod +x "$_tmp" || exit 1
  _LR_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
  export _LR_ROOT
  exec "$_tmp" "$@"
fi

set -u

ROOT="${_LR_ROOT:-$(cd "$(dirname "$0")/.." && pwd)}"
ART="${LR_ART:-$ROOT/artifacts}"
mkdir -p "$ART"

# Окружение: WSL (wslpath) или Git Bash/MSYS (cygpath). Windows-программы в WSL
# вызываются по /mnt/c/... (PS_EXE), но внутри строк PowerShell-команд — только
# Windows-пути (PS_WIN); для cmd нужны //-флаги в MSYS.
if command -v wslpath >/dev/null 2>&1; then
  WINPATH='wslpath -w'
  PS_EXE='/mnt/c/Windows/System32/WindowsPowerShell/v1.0/powershell.exe'
  PS_WIN='C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe'
  CMD='/mnt/c/Windows/System32/cmd.exe'
  SLASH='/'
else
  WINPATH='cygpath -w'
  PS_EXE='C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe'
  PS_WIN='C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe'
  CMD='cmd'
  SLASH='//'
fi

winpath() { $WINPATH "$1" 2>/dev/null || echo "$1"; }

pid_of() { cat "$ART/$1.pid" 2>/dev/null; }

alive() {
  [ -f "$ART/$1.pid" ] || return 1
  local pid; pid=$(pid_of "$1")
  [ -n "$pid" ] || return 1
  "$PS_EXE" -NoProfile -Command "if (Get-Process -Id $pid -ErrorAction SilentlyContinue) { exit 0 } else { exit 1 }" 2>/dev/null
}

start() {
  local name="$1"; shift
  [ "${1:-}" = "--" ] && shift
  [ $# -ge 1 ] || { echo "usage: long-run.sh start <name> -- <command...>"; exit 2; }
  if alive "$name"; then echo "ОШИБКА: '$name' уже запущен (pid $(pid_of "$name"))"; exit 1; fi

  local log="$ART/$name.log" err="$ART/$name.err" pidf="$ART/$name.pid"
  : > "$log"; : > "$err"
  local cmd; cmd="$*"
  # одинарные кавычки PS внутри '...' экранируются удвоением
  local cmd_esc; cmd_esc=$(printf '%s' "$cmd" | sed "s/'/''/g")
  local log_win err_win pid_win
  log_win=$(winpath "$log"); err_win=$(winpath "$err"); pid_win=$(winpath "$pidf")

  "$PS_EXE" -NoProfile -Command "Start-Process -FilePath '$PS_WIN' -ArgumentList '-NoProfile','-Command','$cmd_esc' -RedirectStandardOutput '$log_win' -RedirectStandardError '$err_win' -WindowStyle Hidden -PassThru | Select-Object -ExpandProperty Id | Set-Content '$pid_win'" \
    || { echo "ОШИБКА: не удалось запустить"; exit 1; }

  echo "запущен '$name': $cmd"
  echo "лог:  artifacts/$name.log (.err)"
  echo "pid:  $(pid_of "$name")"
}

status() {
  local name="$1"
  if alive "$name"; then
    local pid; pid=$(pid_of "$name")
    local started; started=$(stat -c %y "$ART/$name.log" 2>/dev/null | cut -d. -f1)
    echo "STATUS: '$name' РАБОТАЕТ (pid=$pid, старт $started)"
    log_tail "$name" 6
  else
    echo "STATUS: '$name' НЕ запущен (или завершился; см. artifacts/$name.log)"
    [ -f "$ART/$name.log" ] && log_tail "$name" 6
  fi
}

log_tail() {
  local name="$1" n="${2:-20}"
  echo "--- artifacts/$name.log (последние $n):"
  tail -n "$n" "$ART/$name.log" 2>/dev/null || echo "(лог пуст)"
  if [ -s "$ART/$name.err" ]; then
    echo "--- artifacts/$name.err:"
    tail -n "$n" "$ART/$name.err" 2>/dev/null
  fi
}

stop() {
  local name="$1" pid
  pid=$(pid_of "$name")
  if [ -n "$pid" ]; then
    echo "останавливаю '$name' (pid=$pid)"
    "$CMD" "${SLASH}c" "taskkill /F /T /PID $pid" 2>&1 | head -3
  fi
  rm -f "$ART/$name.pid"
}

list() {
  local f n pid
  for f in "$ART"/*.pid; do
    [ -f "$f" ] || continue
    n=$(basename "$f" .pid)
    pid=$(cat "$f")
    if kill -0 "$pid" 2>/dev/null || "$PS_EXE" -NoProfile -Command "Get-Process -Id $pid -ErrorAction SilentlyContinue" >/dev/null 2>&1; then
      echo "$n (pid=$pid) — РАБОТАЕТ"
    else
      echo "$n (pid=$pid) — мёртв (лог в artifacts/$n.log)"
    fi
  done
}

case "${1:-}" in
  start)  shift; start "$@" ;;
  status) shift; status "${1:?usage: long-run.sh status <name>}" ;;
  log)    shift; log_tail "${1:?usage: long-run.sh log <name> [N]}" "${2:-20}" ;;
  stop)   shift; stop "${1:?usage: long-run.sh stop <name>}" ;;
  list)   list ;;
  *) echo "usage: long-run.sh {start <name> -- <cmd...>|status <name>|log <name> [N]|stop <name>|list}"; exit 2 ;;
esac
