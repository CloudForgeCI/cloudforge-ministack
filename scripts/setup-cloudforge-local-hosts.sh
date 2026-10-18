#!/usr/bin/env bash
# Set up or remove CloudForge *.cloudforge.localhost hosts entries (MiniStack + LocalStack).
#
# Mainly needed on Windows and older Linux (no systemd-resolved / nss-mdns): those resolvers
# don't auto-resolve the reserved *.localhost TLD (RFC 6761) the way macOS and most modern Linux
# do. On a resolver that already handles it, this script has nothing to fix — it's a compatibility
# shim, not a requirement.
#
# Usage:
#   ./scripts/setup-cloudforge-local-hosts.sh           # setup / refresh (static + live dynamic)
#   ./scripts/setup-cloudforge-local-hosts.sh --remove  # uninstall
#   ./scripts/setup-cloudforge-local-hosts.sh --dry-run # print blocks only, no write
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SNIPPET="$ROOT/docs/guides/examples/cloudforge.localhost.hosts"
HOSTS_FILE="${CFC_HOSTS_FILE:-/etc/hosts}"
EDGE_CONTAINER="${CFC_EDGE_CONTAINER:-cfc-emulator-edge}"

BEGIN="# BEGIN CloudForge local emulator (*.localhost)"
END="# END CloudForge local emulator (*.localhost)"
DYN_BEGIN="# BEGIN CloudForge dynamic instances (auto-generated, do not edit)"
DYN_END="# END CloudForge dynamic instances (auto-generated, do not edit)"

if [[ ! -f "$SNIPPET" ]]; then
  echo "error: missing snippet: $SNIPPET" >&2
  exit 1
fi

MODE=setup
case "${1:-}" in
  --remove|-r) MODE=remove ;;
  --dry-run|-n) MODE=dry-run ;;
  --help|-h)
    sed -n '2,15p' "$0"
    exit 0
    ;;
  "") ;;
  *)
    echo "error: unknown option: $1 (use --remove, --dry-run, or --help)" >&2
    exit 1
    ;;
esac

# Discover currently-active per-instance hostnames (e.g. jenkins1.cloudforge.localhost,
# jenkins2.cloudforge.localhost) straight from the running edge's reconciled nginx config —
# the same source the edge routes on, so it matches what is currently deployed. Returns nothing
# when the edge isn't running or has no routes yet (a normal state); setup still writes the
# static block.
dynamic_hostnames() {
  local conf
  conf="$(docker exec "$EDGE_CONTAINER" cat /etc/nginx/conf.d/cloudforge-apps.conf 2>/dev/null || true)"
  if [[ -z "$conf" ]]; then
    return 0
  fi
  grep -oE 'server_name +[^;]+;' <<<"$conf" \
    | sed -E 's/^server_name +//; s/;$//' \
    | tr ' ' '\n' \
    | grep -v '^_$' \
    | grep -v '^$' \
    | sort -u \
    | while read -r host; do
        # Skip names the static snippet already covers — no need for a duplicate entry.
        if ! grep -qw "$host" "$SNIPPET"; then
          echo "$host"
        fi
      done
}

render_dynamic_block() {
  local hosts
  hosts="$(dynamic_hostnames)"
  if [[ -z "$hosts" ]]; then
    return 0
  fi
  echo "$DYN_BEGIN"
  echo "# Instance hostnames currently active on $EDGE_CONTAINER — re-run setup after"
  echo "# deploying/destroying stacks to refresh. Not hand-maintained; safe to regenerate."
  while read -r host; do
    echo "127.0.0.1 $host"
  done <<<"$hosts"
  echo "$DYN_END"
}

if [[ "$MODE" == "dry-run" ]]; then
  cat "$SNIPPET"
  echo
  render_dynamic_block
  exit 0
fi

if [[ ! -w "$HOSTS_FILE" ]]; then
  if ! command -v sudo >/dev/null 2>&1; then
    echo "error: $HOSTS_FILE is not writable and sudo is unavailable" >&2
    exit 1
  fi
  SUDO=(sudo)
else
  SUDO=()
fi

TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT

# Drop current CloudForge blocks (static + dynamic) — setup always regenerates from scratch
# rather than trying to patch an existing block in place, so it's safe to re-run any time.
awk -v begin="$BEGIN" -v end="$END" -v dynb="$DYN_BEGIN" -v dyne="$DYN_END" '
  $0 == begin || $0 == dynb { skip=1; next }
  $0 == end || $0 == dyne { skip=0; next }
  !skip { print }
' "$HOSTS_FILE" > "$TMP"

if [[ "$MODE" == "setup" ]]; then
  if [[ -s "$TMP" ]] && [[ "$(tail -c1 "$TMP" | wc -c)" -ne 0 ]]; then
    printf '\n' >> "$TMP"
  fi
  cat "$SNIPPET" >> "$TMP"
  printf '\n' >> "$TMP"
  DYNAMIC_BLOCK="$(render_dynamic_block)"
  if [[ -n "$DYNAMIC_BLOCK" ]]; then
    printf '%s\n' "$DYNAMIC_BLOCK" >> "$TMP"
  fi
  "${SUDO[@]}" cp "$TMP" "$HOSTS_FILE"
  echo "Set up CloudForge hosts block in $HOSTS_FILE"
  if [[ -n "$DYNAMIC_BLOCK" ]]; then
    echo "Also added live instance hostnames from $EDGE_CONTAINER:"
    grep '^127.0.0.1' <<<"$DYNAMIC_BLOCK" | sed 's/^/  /'
  fi
  echo "Open with http:// (bare names may Google-search):"
  echo "  open http://nginx.localhost/"
  echo "  open http://localstack.localhost/"
  echo "  open http://stackport.localhost/"
  echo "Docs: docs/guides/LOCAL_EMULATOR_HOSTS.md"
else
  "${SUDO[@]}" cp "$TMP" "$HOSTS_FILE"
  echo "Removed CloudForge hosts block from $HOSTS_FILE"
fi
