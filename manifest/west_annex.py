"""west 拡張: DataLad / git-annex の実体を Backblaze B2 と同期する。

west.yml で ``userdata: {datalad: true, annex-remote: <name>}`` が付いた project を
対象に、B2 (S3 互換) special remote を有効化して content を取得/破棄する。
git にはポインタ(annex キー)だけが入り、実体は B2 に置く運用(CLAUDE.md 準拠)。

認証は ``scripts/b2-creds.bb`` が解決する(既定 env→1Password→Keychain。参照先は
``manifest/repos.edn`` の ``:b2 :credentials``)。リポジトリには秘密情報を置かない。

使い方::

    west update --group-filter +datalad m365-archive   # git/annex スケルトンを取得
    west annex-get                                      # 実体を B2 から取得
    west annex-drop                                     # ローカル実体を破棄(B2 は保持)
"""
import json as _json
import os
import subprocess
from shutil import which

from west import log
from west.commands import WestCommand


def _datalad_projects(manifest):
    out = []
    for p in manifest.projects:
        ud = getattr(p, "userdata", None) or {}
        if isinstance(ud, dict) and ud.get("datalad"):
            out.append(p)
    return out


def _run(cmd, cwd, env=None):
    log.inf("  $ " + " ".join(cmd) + "   (in " + cwd + ")")
    return subprocess.run(cmd, cwd=cwd, env=env).returncode


def _has(exe):
    return which(exe) is not None


def _resolve_b2(topdir):
    """scripts/b2-creds.bb で B2 creds を解決(env→1Password→Keychain)。

    返り値は {"B2_KEY_ID":..., "AWS_ACCESS_KEY_ID":..., ...} か None。
    """
    script = os.path.join(topdir, "scripts", "b2-creds.bb")
    if not os.path.exists(script):
        log.wrn("scripts/b2-creds.bb が無いため B2 creds を解決できません。")
        return None
    if not _has("bb"):
        log.wrn("bb (babashka) が見つかりません。B2 creds を解決できません。")
        return None
    res = subprocess.run(["bb", script, "--json"], cwd=topdir,
                         capture_output=True, text=True)
    if res.returncode != 0:
        log.wrn("B2 creds 解決に失敗:", (res.stderr or "").strip())
        return None
    try:
        return _json.loads(res.stdout)
    except ValueError:
        log.wrn("b2-creds の出力を解釈できません。")
        return None


def _enable_b2(cwd, remote, env):
    """annex を init し、解決済み creds(env)で special remote を有効化(冪等)。"""
    _run(["git", "annex", "init"], cwd)
    if not env.get("AWS_ACCESS_KEY_ID"):
        log.wrn("B2 creds 未解決。manifest/repos.edn の :b2 :credentials を確認(get/drop skip)。")
        return False
    rc = _run(["git", "annex", "enableremote", remote], cwd, env=env)
    if rc != 0:
        log.wrn("enableremote", remote,
                "失敗。初回は scripts/datalad-b2-init.bb で initremote 済みか確認。")
        return False
    return True


class _AnnexBase(WestCommand):
    def do_add_parser(self, parser_adder):
        parser = parser_adder.add_parser(
            self.name, help=self.help, description=self.description)
        parser.add_argument(
            "projects", nargs="*",
            help="対象 project 名(既定: datalad 印の付いた全 project)")
        return parser

    def _targets(self, args):
        dls = _datalad_projects(self.manifest)
        if args.projects:
            names = set(args.projects)
            dls = [p for p in dls if p.name in names]
        if not dls:
            log.inf("datalad 印の付いた project がありません。")
        return dls


class AnnexGet(_AnnexBase):
    def __init__(self):
        super().__init__(
            "annex-get",
            "fetch git-annex/DataLad content from B2 for datalad-tagged projects",
            __doc__)

    def do_run(self, args, unknown):
        creds = _resolve_b2(self.topdir) or {}
        env = {**os.environ, **creds}
        for p in self._targets(args):
            ud = p.userdata or {}
            remote = ud.get("annex-remote", "b2")
            log.banner("annex-get:", p.name)
            if not p.is_cloned():
                log.wrn(p.name, "は未取得。先に: west update --group-filter +datalad", p.name)
                continue
            if not _enable_b2(p.abspath, remote, env):
                continue
            if _has("datalad"):
                _run(["datalad", "get", "."], p.abspath, env=env)
            else:
                _run(["git", "annex", "get", "--from", remote], p.abspath, env=env)


class AnnexDrop(_AnnexBase):
    def __init__(self):
        super().__init__(
            "annex-drop",
            "drop local git-annex content (keep the B2 copy)",
            __doc__)

    def do_run(self, args, unknown):
        creds = _resolve_b2(self.topdir) or {}
        env = {**os.environ, **creds}
        for p in self._targets(args):
            log.banner("annex-drop:", p.name)
            if not p.is_cloned():
                continue
            # numcopies を検証してから破棄(B2 にコピーが残ることを確認)
            if _has("datalad"):
                _run(["datalad", "drop", "."], p.abspath, env=env)
            else:
                _run(["git", "annex", "drop"], p.abspath, env=env)
