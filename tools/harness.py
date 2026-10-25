#!/usr/bin/env python3
"""Small, dependency-free project bootstrap and agent second-brain CLI."""

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import subprocess
import sys


# Copies travel into other repos; --version tells which release a copy came from. Bump with each tag.
VERSION = "0.1.0"
ROOT = Path(__file__).resolve().parents[1]
BRAIN = ROOT / "brain"
FILES = {
    "README.project.md": "README.md",
    "AGENTS.md": "AGENTS.md",
    "LICENSE": "LICENSE",
    ".gitignore": ".gitignore",
    ".gitattributes": ".gitattributes",
    ".github/workflows/verify.yml": ".github/workflows/verify.yml",
    ".github/dependabot.yml": ".github/dependabot.yml",
    "SECURITY.md": "SECURITY.md",
    "tools/harness.py": "tools/harness.py",
    "tests/test_harness.py": "tests/test_harness.py",
    "docs/REPO_INIT_PROMPT.md": "docs/REPO_INIT_PROMPT.md",
    "brain/INDEX.md": "brain/INDEX.md",
    "docs/PROJECT.template.md": "brain/PROJECT.md",
    "brain/STATE.md": "brain/STATE.md",
    "brain/DECISIONS.md": "brain/DECISIONS.md",
    "brain/MCP.md": "brain/MCP.md",
}
# Task scope -> notes, excerpt chars, compact-summary tokens. Heuristic sizes; tune from real sessions.
SCOPES = {"tiny": (1, 400, 3000), "bounded": (3, 800, 6000), "system": (5, 1600, 12000)}
SIZES = ("small", "medium", "large")
START = "<!-- harness:recent:start -->"
END = "<!-- harness:recent:end -->"
WIKI = re.compile(r"\[\[([^\]]+)\]\]")
DECISION_MARKER = "<!-- harness:decisions:start -->"
# shortcut: common token shapes only, not a full scanner; add gitleaks if the project grows.
# PEM headers count only as a key line or an escaped inline key; code that builds or strips PEM text does not.
SECRET = re.compile(r"^\s*-----BEGIN [A-Z ]*PRIVATE KEY-----\s*$|-----BEGIN [A-Z ]*PRIVATE KEY-----\\n[A-Za-z0-9+/]"
                    r"|AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9]{36}"
                    r"|github_pat_[A-Za-z0-9_]{22,}|\bsk-[A-Za-z0-9_-]{20,}|xox[abprs]-[A-Za-z0-9-]{10,}")
# adopt: harness files added to an existing repo; its README, LICENSE, CI and tests stay untouched.
ADOPT = ("tools/harness.py", "brain/INDEX.md", "brain/STATE.md", "brain/DECISIONS.md", "brain/MCP.md")
AGENTS_START, AGENTS_END = "<!-- initium:start -->", "<!-- initium:end -->"
AGENTS_BLOCK = f"""{AGENTS_START}
## Initium harness

Start with `python3 tools/harness.py context --query "<task>" --scope tiny|bounded|system` and load only the MCP servers its Budget footer lists. Finish by updating affected `brain/` notes, then `harness.py complete` (write `not run` for anything unrun) and `harness.py check`. Run `harness.py preflight` before any push. On Windows use `python`.
{AGENTS_END}
"""
# Root or one-level-down marker -> candidate verify command, recorded unverified in brain/PROJECT.md.
BUILDS = {"gradlew": "./gradlew test", "pom.xml": "mvn test", "package.json": "npm test",
          "pyproject.toml": "python -m pytest", "Cargo.toml": "cargo test", "go.mod": "go test ./..."}
GUIDES = ("AGENTS.md", "CLAUDE.md", "CONTRIBUTING.md", "docs/README.md", ".github/copilot-instructions.md")


def replace_block(path: Path, value: str) -> None:
    original = path.read_text(encoding="utf-8")
    if original.count(START) != 1 or original.count(END) != 1:
        raise ValueError(f"Missing or duplicate managed block in {path}")
    before, rest = original.split(START, 1)
    _, after = rest.split(END, 1)
    path.write_text(f"{before}{START}\n{value.rstrip()}\n{END}{after}", encoding="utf-8", newline="\n")


def managed_block(path: Path) -> str:
    content = path.read_text(encoding="utf-8")
    if content.count(START) != 1 or content.count(END) != 1:
        raise ValueError(f"Missing or duplicate managed block in {path}")
    return content.split(START, 1)[1].split(END, 1)[0].strip()


def write_starter(src: Path, target: Path, rel: str, name: str, description: str | None, extra: str = "") -> None:
    content = src.read_text(encoding="utf-8")
    # Render project documents only; source and reusable templates stay literal.
    if rel in ("README.md", "brain/PROJECT.md"):
        replacements = {"{{PROJECT_NAME}}": name,
                        "{{DESCRIPTION}}": description or "TODO: describe the problem and first use case"}
        for old, new in replacements.items():
            content = content.replace(old, new)
    if rel in ("brain/INDEX.md", "brain/STATE.md"):
        fresh = "No completed work recorded yet." if target.name == "STATE.md" else ""
        # Initium's own goal is not the new project's goal.
        content = re.sub(r"^Current goal: .*$", "Current goal: define and verify the smallest project MVP.",
                         content, flags=re.MULTILINE)
        content = re.sub(re.escape(START) + r".*?" + re.escape(END),
                         START + "\n" + fresh + "\n" + END, content, flags=re.DOTALL)
    elif rel == "brain/DECISIONS.md":
        content = content.split(DECISION_MARKER, 1)[0] + DECISION_MARKER + "\n"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content + extra, encoding="utf-8", newline="\n")


def adopt(args: argparse.Namespace) -> None:
    dest = Path(args.dir).expanduser().resolve()
    if not (dest / ".git").exists():
        raise ValueError(f"Not a git repository root: {dest} (use init for a new project)")
    added, kept = [], []
    # A worktree or temp clone has an arbitrary folder name; the origin URL names the real repo.
    origin = subprocess.run(["git", "-C", str(dest), "remote", "get-url", "origin"],
                            capture_output=True, text=True).stdout.strip()
    name = re.split(r"[/:]", origin.rstrip("/"))[-1].removesuffix(".git") or dest.name
    builds = [f"`{'' if path.parent == dest else 'cd ' + path.parent.name + ' && '}{command}`"
               for marker, command in BUILDS.items()
               for path in sorted([dest / marker, *dest.glob(f"*/{marker}")]) if path.is_file()]
    guides = [f"`{guide}`" for guide in GUIDES if (dest / guide).is_file()]
    # Real repos run e.g. `gradlew check` or `mvn -B test` in CI, so point at the workflows as the source of truth.
    ci = [f"`{path.relative_to(dest).as_posix()}`" for path in sorted((dest / ".github/workflows").glob("*.y*ml"))]
    found = (f"\n## Found in the existing repo\n\n- Candidate checks (unverified; confirm, then edit): "
             f"{', '.join(builds) or 'none detected'}\n- CI workflows (the commands CI really runs): "
             f"{', '.join(ci) or 'none'}\n- Existing guidance to follow first: {', '.join(guides) or 'none'}\n")
    for rel in (*ADOPT, "brain/PROJECT.md"):
        target = dest / rel
        if target.exists():
            kept.append(rel)
            continue
        src = ROOT / ("docs/PROJECT.template.md" if rel == "brain/PROJECT.md" else rel)
        write_starter(src, target, rel, name, args.description, found if rel == "brain/PROJECT.md" else "")
        added.append(rel)
    agents = dest / "AGENTS.md"
    if not agents.exists():
        agents.write_text(f"# {name} agent instructions\n\n{AGENTS_BLOCK}", encoding="utf-8", newline="\n")
        added.append("AGENTS.md")
    elif AGENTS_START in agents.read_text(encoding="utf-8"):
        kept.append("AGENTS.md")
    else:
        # Append, never rewrite: the repo's own agent rules stay authoritative.
        with agents.open("a", encoding="utf-8", newline="\n") as handle:
            handle.write("\n" + AGENTS_BLOCK)
        added.append("AGENTS.md (appended Initium section)")
    print(f"Adopted {dest}\nAdded: {', '.join(added) or 'nothing'}\nKept existing: {', '.join(kept) or 'none'}")
    print("Next: fill brain/PROJECT.md scope, confirm its candidate checks, then run check and preflight.")


def bootstrap(args: argparse.Namespace) -> None:
    name = args.name
    if not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", name) or len(name) > 63:
        raise ValueError("Name must be kebab-case, 1-63 characters")
    dest = Path(args.dir or name).expanduser().resolve()
    if dest.exists():
        raise ValueError(f"Refusing to overwrite existing path: {dest}")
    sources = [(ROOT / src, dest / target) for src, target in FILES.items()]
    # Allow a generated project to use the same CLI and regression check.
    sources.append((ROOT / "README.project.md", dest / "README.project.md"))
    sources.append((ROOT / "docs/PROJECT.template.md", dest / "docs/PROJECT.template.md"))
    missing = [str(src) for src, _ in sources if not src.is_file()]
    if missing:
        raise FileNotFoundError("Missing template files: " + ", ".join(missing))

    for src, target in sources:
        write_starter(src, target, target.relative_to(dest).as_posix(), name, args.description)
    print(f"Created {dest}")
    print(f"Next: cd {dest} && python3 tools/harness.py context  (use python on Windows)")
    print("After a working MVP, commit, pass preflight, then publish only when authorized:")
    print("  git init -b main && git add . && python3 tools/harness.py preflight && git commit -m 'Initial project'")
    print(f"  gh repo create sunJirapath/{name} --public --source . --remote origin --push")


def route(args: argparse.Namespace) -> None:
    score = {"tiny": 0, "bounded": 2, "system": 4}[args.scope]
    score += {"low": 0, "medium": 2, "high": 4}[args.risk]
    score += {"low": 0, "medium": 1, "high": 2}[args.uncertainty]
    if args.risk == "high" or score >= 6:
        tier, effort = "capable", "high"
    elif score >= 3:
        tier, effort = "balanced", "medium"
    else:
        tier, effort = "fast", "low"
    agents = min(3, args.independent) if args.scope != "tiny" and args.independent > 1 else 0
    print(json.dumps({
        "task": args.task, "score": score, "suggested_subagent_tier": tier,
        "reasoning_effort": effort, "max_parallel_subagents": agents,
        "main_agent": "most capable suitable model; owns integration and final verification",
        "note": "Advisory only: main decides if delegation beats coordination overhead.",
    }, indent=2))


def relevant_notes(query: str, maximum: int) -> list[Path]:
    terms = set(re.findall(r"[\w-]+", query.casefold()))
    if not terms:
        return []
    ranked = []
    for path in BRAIN.rglob("*.md"):
        if path.name in ("INDEX.md", "STATE.md", "MCP.md"):
            continue
        content = path.read_text(encoding="utf-8").casefold()
        score = sum((3 if term in path.stem.casefold() else 0) + min(content.count(term), 2) for term in terms)
        if score:
            ranked.append((-score, str(path), path))
    return [path for _, _, path in sorted(ranked)[:maximum]]


def excerpt(path: Path, terms: set[str], chars: int) -> str:
    text = path.read_text(encoding="utf-8")
    if path.parent.name == "tasks":
        # Title, outcome and next carry the signal; date and verification stay in the note.
        sections = dict(re.findall(r"^## (\w+)\n\n(.*?)(?=\n## |\Z)", text, re.MULTILINE | re.DOTALL))
        return f"{text.splitlines()[0]}\nOutcome: {sections.get('Outcome', '').strip()}\nNext: {sections.get('Next', '').strip()}"[:chars]
    # Matching lines, newest last: DECISIONS.md is append-only, so a head slice would hide recent choices.
    hits = [line for line in text.splitlines() if any(term in line.casefold() for term in terms)]
    return "\n".join(hits[-8:])[-chars:] or text[:chars // 2]


def project_tokens() -> int | None:
    try:
        names = [name for name in git("ls-files", "-z").split("\0") if name]
    except (OSError, ValueError):
        return None  # No git repo yet: report unknown rather than guess from untracked files.
    return sum((ROOT / name).stat().st_size for name in names if (ROOT / name).is_file()) // 4


def suggested_mcp(terms: set[str]) -> list[str]:
    path = BRAIN / "MCP.md"
    if not path.is_file():
        return []
    entries = re.findall(r"^- ([\w.-]+): (.+?) — (.+)$", path.read_text(encoding="utf-8"), re.MULTILINE)
    return [f"{name} ({use})" for name, keywords, use in entries
            if terms & {word.strip().casefold() for word in keywords.split(",")}]


def context(args: argparse.Namespace) -> None:
    # Deliberately bounded: main sees an index and summaries, not the entire vault.
    notes, chars, summary = SCOPES[args.scope]
    index = (BRAIN / "INDEX.md").read_text(encoding="utf-8")
    recent = managed_block(BRAIN / "INDEX.md")
    out = [index.replace(recent, "\n".join(recent.splitlines()[:3])).strip(),
           (BRAIN / "STATE.md").read_text(encoding="utf-8")[:1500].strip()]
    terms = set(re.findall(r"[\w-]+", (args.query or "").casefold()))
    limit = notes if args.max_notes is None else min(5, max(0, args.max_notes))
    for path in relevant_notes(args.query or "", limit):
        out.append(f"## {path.relative_to(ROOT).as_posix()}\n{excerpt(path, terms, chars)}")
    # shortcut: chars/4 approximates tokens; use the host's tokenizer if budgets get tight.
    size = project_tokens()
    tier = 1 if size is None else (0 if size < 20_000 else 1 if size < 200_000 else 2)
    scale = list(SCOPES).index(args.scope)
    compact_at = round(args.window * (0.45 + 0.1 * scale + 0.05 * tier), -3)
    label = "unknown (no git repo)" if size is None else f"~{size} tokens tracked ({SIZES[tier]})"
    mcp = suggested_mcp(terms)
    out.append(f"## Budget\nscope={args.scope}: {limit} notes, {chars}-char excerpts; "
               f"briefing ~{len(chr(10).join(out)) // 4} tokens\nproject size: {label}\n"
               f"compact at ~{compact_at:.0f} of {args.window} tokens; keep the summary <= ~{summary} tokens "
               "(goal, touched files, decisions, failing checks)\n"
               f"MCP: {'; '.join(mcp) if mcp else 'none matched; use built-in file, search and shell tools'}")
    print("\n\n".join(out))


def complete(args: argparse.Namespace) -> None:
    slug = re.sub(r"[^a-z0-9]+", "-", args.title.casefold()).strip("-")[:48] or "task"
    timestamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    task_dir = BRAIN / "tasks"
    task_dir.mkdir(parents=True, exist_ok=True)
    path = task_dir / f"{timestamp}-{slug}.md"
    if path.exists():
        raise ValueError(f"Task note already exists: {path}")
    index, state = BRAIN / "INDEX.md", BRAIN / "STATE.md"
    old_recent = managed_block(index)
    managed_block(state)  # Validate both files before writing any changes.
    link = f"[[tasks/{path.stem}]]"
    note = (f"# {args.title}\n\nDate: {datetime.now(timezone.utc).date().isoformat()} UTC\n\n"
            f"## Outcome\n\n{args.summary}\n\n## Verification\n\n{args.verified or 'Not run / not verified'}\n\n"
            f"## Decision\n\n{args.decision or 'No durable decision'}\n\n## Next\n\n{args.next or 'None'}\n")
    path.write_text(note, encoding="utf-8", newline="\n")
    history = [line for line in old_recent.splitlines() if line.startswith("- ")]
    replace_block(index, "\n".join([f"- {link} — {args.title}", *history[:7]]))
    replace_block(state, f"Last completed: {link}\n\nNext: {args.next or 'None'}")
    if args.decision:
        with (BRAIN / "DECISIONS.md").open("a", encoding="utf-8", newline="\n") as handle:
            handle.write(f"\n- {datetime.now(timezone.utc).date().isoformat()}: {args.decision} (evidence: {link})\n")
    print(f"Second brain updated: {path.relative_to(ROOT)}, brain/INDEX.md, brain/STATE.md")


def check(_: argparse.Namespace) -> None:
    notes = list(BRAIN.rglob("*.md"))
    by_stem: dict[str, list[Path]] = {}
    for path in notes:
        by_stem.setdefault(path.stem.casefold(), []).append(path)
    errors = []
    for path in notes:
        for raw in WIKI.findall(path.read_text(encoding="utf-8")):
            target = raw.split("|", 1)[0].split("#", 1)[0].strip()
            parts = Path(target).parts
            if not target or target.startswith("/") or ".." in parts:
                errors.append(f"{path.relative_to(ROOT)}: unsafe link [[{raw}]]")
                continue
            matches = ([BRAIN / (target + ".md")] if "/" in target else by_stem.get(target.casefold(), []))
            if len(matches) != 1 or not matches[0].is_file():
                errors.append(f"{path.relative_to(ROOT)}: unresolved/ambiguous [[{raw}]]")
    if errors:
        raise ValueError("\n".join(errors))
    print(f"OK: {len(notes)} brain notes, wiki links resolved")


def git(*args: str) -> str:
    result = subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True, encoding="utf-8")
    if result.returncode:
        raise ValueError(f"git {args[0]} failed: {result.stderr.strip() or 'no output'}")
    return result.stdout


def preflight(_: argparse.Namespace) -> None:
    # Pre-publish gate over tracked files; reports locations only, never matched values.
    errors = [f"{name}: tracked but matches .gitignore" for name in
              filter(None, git("ls-files", "-z", "-ci", "--exclude-standard").split("\0"))]
    # Judge the index, not the checkout: autocrlf rewrites working files, and -text/eol=crlf files opt in.
    for info, _, name in (entry.partition("\t") for entry in git("ls-files", "--eol", "-z").split("\0") if entry):
        if info.split()[0] in ("i/crlf", "i/mixed") and "-text" not in info and "eol=crlf" not in info:
            errors.append(f"{name}: CRLF line endings")
    tracked = [name for name in git("ls-files", "-z").split("\0") if name]
    for name in tracked:
        path = ROOT / name
        if not path.is_file():
            continue
        data = path.read_bytes()
        if b"\0" in data:
            continue
        for number, line in enumerate(data.decode("utf-8", "replace").splitlines(), 1):
            if SECRET.search(line):
                errors.append(f"{name}:{number}: possible secret (value not shown)")
    if not (ROOT / "LICENSE").is_file():
        errors.append("LICENSE is missing")
    email = subprocess.run(["git", "config", "user.email"], cwd=ROOT, capture_output=True, text=True).stdout
    if not email.strip().endswith("@users.noreply.github.com"):
        errors.append("git user.email (this clone's config, used for new commits) is not a GitHub noreply address")
    if errors:
        raise ValueError("\n".join(errors))
    print(f"OK: {len(tracked)} tracked files; no secrets, CRLF or ignored files; LICENSE and noreply email set")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", action="version", version=f"Initium harness {VERSION}")
    commands = parser.add_subparsers(dest="command", required=True)
    init = commands.add_parser("init", help="Copy starter into a new directory; never copies .git or secrets")
    init.add_argument("name")
    init.add_argument("--dir")
    init.add_argument("--description")
    init.set_defaults(func=bootstrap)

    join = commands.add_parser("adopt", help="Add harness files to an existing git repo; never overwrites")
    join.add_argument("--dir", default=".")
    join.add_argument("--description")
    join.set_defaults(func=adopt)

    triage = commands.add_parser("route", help="Advisory model and reasoning-effort triage")
    triage.add_argument("--task", required=True)
    triage.add_argument("--scope", choices=["tiny", "bounded", "system"], required=True)
    triage.add_argument("--risk", choices=["low", "medium", "high"], required=True)
    triage.add_argument("--uncertainty", choices=["low", "medium", "high"], required=True)
    triage.add_argument("--independent", type=int, default=0, help="Number of independent subtasks")
    triage.set_defaults(func=route)

    load = commands.add_parser("context", help="Read a bounded second-brain briefing")
    load.add_argument("--query", default="")
    load.add_argument("--scope", choices=list(SCOPES), default="bounded", help="Task size; sets the note budget")
    load.add_argument("--max-notes", type=int, help="Override the scope's note count (0-5)")
    load.add_argument("--window", type=int, default=200_000, help="Host context window in tokens")
    load.set_defaults(func=context)

    done = commands.add_parser("complete", help="Persist verified work into linked Markdown notes")
    done.add_argument("--title", required=True)
    done.add_argument("--summary", required=True)
    done.add_argument("--verified")
    done.add_argument("--decision")
    done.add_argument("--next")
    done.set_defaults(func=complete)

    commands.add_parser("check", help="Validate second-brain wiki links").set_defaults(func=check)
    commands.add_parser("preflight", help="Pre-publish scan: secrets, CRLF, ignored files, LICENSE, "
                        "noreply email").set_defaults(func=preflight)
    args = parser.parse_args()
    try:
        args.func(args)
    except (OSError, ValueError) as exc:
        parser.exit(1, f"Error: {exc}\n")


if __name__ == "__main__":
    main()
