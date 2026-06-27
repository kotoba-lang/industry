"""west 拡張: DataLad / git-annex の実体を Backblaze B2 と同期する。

west.yml で ``userdata: {datalad: true, annex-remote: <name>}`` が付いた project を
対象に、B2 (S3 互換) special remote を有効化して content を取得/破棄する。
git にはポインタ(annex キー)だけが入り、実体は B2 に置く運用(CLAUDE.md 準拠)。

認証は環境変数のみ: ``B2_KEY_ID`` / ``B2_APP_KEY`` / ``B2_BUCKET``。
リポジトリには秘密情報を一切コミットしない。

使い方::

    west update --group-filter +datalad m365-archive   # git/annex スケルトンを取得
    west annex-get                                      # 実体を B2 から取得
    west annex-drop                                     # ローカル実体を破棄(B2 は保持)
"""
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


def _enable_b2(cwd, remote):
    """annex を init し、環境変数の B2 creds で special remote を有効化(冪等)。"""
    _run(["git", "annex", "init"], cwd)
    key = os.environ.get("B2_KEY_ID")
    app = os.environ.get("B2_APP_KEY")
    bucket = os.environ.get("B2_BUCKET")
    if not (key and app and bucket):
        log.wrn("B2_KEY_ID / B2_APP_KEY / B2_BUCKET が未設定。remote", remote,
                "を有効化できません(get/drop はスキップ)。")
        return False
    env = os.environ.copy()
    # git-annex の S3 special remote は AWS_* を読む
    env["AWS_ACCESS_KEY_ID"] = key
    env["AWS_SECRET_ACCESS_KEY"] = app
    rc = _run(["git", "annex", "enableremote", remote], cwd, env=env)
    if rc != 0:
        log.wrn("enableremote", remote, "失敗。初回は scripts/datalad-b2-init.bb で initremote 済みか確認。")
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
        for p in self._targets(args):
            ud = p.userdata or {}
            remote = ud.get("annex-remote", "b2")
            log.banner("annex-get:", p.name)
            if not p.is_cloned():
                log.wrn(p.name, "は未取得。先に: west update --group-filter +datalad", p.name)
                continue
            if not _enable_b2(p.abspath, remote):
                continue
            if _has("datalad"):
                _run(["datalad", "get", "."], p.abspath)
            else:
                _run(["git", "annex", "get", "--from", remote], p.abspath)


class AnnexDrop(_AnnexBase):
    def __init__(self):
        super().__init__(
            "annex-drop",
            "drop local git-annex content (keep the B2 copy)",
            __doc__)

    def do_run(self, args, unknown):
        for p in self._targets(args):
            log.banner("annex-drop:", p.name)
            if not p.is_cloned():
                continue
            # numcopies を検証してから破棄(B2 にコピーが残ることを確認)
            if _has("datalad"):
                _run(["datalad", "drop", "."], p.abspath)
            else:
                _run(["git", "annex", "drop"], p.abspath)
