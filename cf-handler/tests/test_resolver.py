import base64
from cf_handler.core.resolver import (
    BLACKLIST_HOSTS,
    IP_LOCKED_HOSTS,
    _AnchorIframeParser,
    _classify_host,
)


def test_vidhide_blacklisted_and_healthy_otakudesu_embeds_supported():
    assert "vidhide." in BLACKLIST_HOSTS
    assert "vidhide." not in IP_LOCKED_HOSTS
    assert _classify_host("https://vidhide.com/e/abc")[0] == "skip"
    assert _classify_host("https://odstream.xyz/e/abc")[0] == "ip_locked"
    assert _classify_host("https://gofile.io/d/MYZtgPQN")[0] == "ip_locked"


def test_anchor_iframe_parser_extracts_base64_mirror_options():
    b64 = base64.b64encode(b'<iframe src="https://icefile.web.id/e/hd2"></iframe>').decode("ascii")
    html = f"""
    <select class="mirror">
      <option value="">Select Video Server</option>
      <option value="{b64}">HD-2</option>
    </select>
    <a href="https://gofile.io/d/MYZtgPQN">Download</a>
    """
    p = _AnchorIframeParser()
    p.feed(html)
    assert any(val == b64 and lbl == "HD-2" for val, lbl in p.options)
    assert any("gofile.io/d/MYZtgPQN" in href for href, _, _ in p.anchors)
