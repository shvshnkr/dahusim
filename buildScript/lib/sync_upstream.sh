#!/usr/bin/env bash
set -euo pipefail

UPSTREAM_REMOTE="${UPSTREAM_REMOTE:-upstream}"
UPSTREAM_REF="${UPSTREAM_REF:-dev}"
ORIGIN_REMOTE="${ORIGIN_REMOTE:-origin}"
GITHUB_REMOTE="${GITHUB_REMOTE:-github}"

usage() {
	cat <<'EOF'
Usage:
  ./run lib sync_upstream [command]

Commands:
  status   Fetch upstream/origin/github (if present) and print divergence vs
           upstream/<ref>. Default ref: dev (override with UPSTREAM_REF=...).
  merge    Require clean tree; create branch sync/upstream-YYYYMMDD-HHMMSS from
           HEAD and merge upstream/<ref> (no-ff disabled: merge commit only if
           needed).
  rebase   Require clean tree; create branch sync/upstream-YYYYMMDD-HHMMSS from
           HEAD and rebase onto upstream/<ref>.

Environment:
  UPSTREAM_REMOTE   default upstream
  UPSTREAM_REF      branch name on upstream (default dev)
  ORIGIN_REMOTE     default origin
  GITHUB_REMOTE     default github

Remotes are optional except for the merge/rebase target: upstream must exist.
EOF
}

die() {
	echo "sync_upstream: $*" >&2
	exit 1
}

in_git_repo() {
	git rev-parse --is-inside-work-tree >/dev/null 2>&1
}

have_remote() {
	git remote get-url "$1" >/dev/null 2>&1
}

require_upstream() {
	have_remote "$UPSTREAM_REMOTE" || die "missing git remote '$UPSTREAM_REMOTE'"
}

upstream_full_ref() {
	echo "${UPSTREAM_REMOTE}/${UPSTREAM_REF}"
}

require_clean_tree() {
	if ! git diff-index --quiet HEAD -- 2>/dev/null; then
		echo "sync_upstream: working tree not clean" >&2
		git status --short
		exit 1
	fi
}

do_fetch() {
	local r
	for r in "$UPSTREAM_REMOTE" "$ORIGIN_REMOTE" "$GITHUB_REMOTE"; do
		if have_remote "$r"; then
			echo ">> fetch $r"
			git fetch "$r" --prune
		else
			echo ">> skip fetch (no remote '$r')"
		fi
	done
}

print_divergence() {
	local up
	up="$(upstream_full_ref)"
	require_upstream
	git rev-parse -q --verify "$up" >/dev/null 2>&1 || die "no ref $up (fetch upstream?)"
	local mb
	mb="$(git merge-base HEAD "$up")"
	echo ">> merge-base $(git rev-parse --short "$mb")"
	echo ">> upstream ${up}: $(git rev-parse --short "$up")"
	echo ">> HEAD:         $(git rev-parse --short HEAD)"
	echo ">> commits on HEAD not in ${up} (ahead):"
	git rev-list --count "${up}..HEAD" | awk '{print "   ", $1}'
	echo ">> commits on ${up} not in HEAD (behind):"
	git rev-list --count "HEAD..${up}" | awk '{print "   ", $1}'
	echo ">> left-right log ${up}...HEAD (first 40 lines):"
	git rev-list --left-right --cherry-pick --oneline "${up}...HEAD" | head -n 40 || true
	tot="$(git rev-list --count "${up}...HEAD" 2>/dev/null || echo 0)"
	if [ "${tot:-0}" -gt 40 ]; then
		echo "   (... symmetric range has ${tot} commits; output truncated)"
	fi
}

cmd_status() {
	do_fetch
	print_divergence
	echo ">> next: create integration branch, then merge or rebase from $(upstream_full_ref)"
	echo "   ./run lib sync_upstream merge"
	echo "   ./run lib sync_upstream rebase"
}

integration_branch_name() {
	echo "sync/upstream-$(date +%Y%m%d-%H%M%S)"
}

cmd_merge() {
	require_clean_tree
	require_upstream
	local up b
	up="$(upstream_full_ref)"
	git fetch "$UPSTREAM_REMOTE" --prune
	git rev-parse -q --verify "$up" >/dev/null 2>&1 || die "no ref $up"
	b="$(integration_branch_name)"
	echo ">> checkout -b $b"
	git checkout -b "$b"
	if git merge-base --is-ancestor "$up" HEAD 2>/dev/null; then
		echo ">> $up already contained in HEAD; still on branch $b"
	else
		echo ">> merge $up into $b"
		git merge "$up" -m "Merge $up into $b"
	fi
	echo ">> done. Resolve conflicts if any, run gates, then push to origin/github."
}

cmd_rebase() {
	require_clean_tree
	require_upstream
	local up b
	up="$(upstream_full_ref)"
	git fetch "$UPSTREAM_REMOTE" --prune
	git rev-parse -q --verify "$up" >/dev/null 2>&1 || die "no ref $up"
	b="$(integration_branch_name)"
	echo ">> checkout -b $b"
	git checkout -b "$b"
	echo ">> rebase onto $up"
	git rebase "$up"
	echo ">> done. Resolve conflicts if any, run gates, then push to origin/github."
}

main() {
	in_git_repo || die "not a git repository"
	local sub="${1:-status}"
	case "$sub" in
	status | "")
		cmd_status
		;;
	-h | --help | help)
		usage
		;;
	merge)
		cmd_merge
		;;
	rebase)
		cmd_rebase
		;;
	*)
		usage
		exit 1
		;;
	esac
}

main "$@"
