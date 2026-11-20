import base64
from cf_handler.core.animasu import (
    parse_animasu_detail,
    parse_animasu_episode_mirrors,
    parse_animasu_search,
)


def test_parse_animasu_search_basic():
    html = """
    <div class="postbody"><div class="listupd">
      <div class="bs"><div class="bsx">
        <a href="https://animasu.love/anime/date-a-live-s5/" title="Nonton Anime Date A Live Season 5 Sub Indo">
          <div class="limit">
            <div class="typez TV">TV</div>
            <div class="bt"><span class="epx">12 Episode</span><span class="sb">Selesai ✓</span></div>
            <img src="https://i2.wp.com/animasu.love/wp-content/uploads/2024/02/141438.jpg" class="wp-post-image"/>
          </div>
          <div class="tt">Date A Live Season 5</div>
        </a>
      </div></div>
    </div></div>
    """
    items = parse_animasu_search(html, "https://animasu.love")
    assert len(items) == 1
    assert items[0]["slug"] == "date-a-live-s5"
    assert items[0]["title"] == "Date A Live Season 5"
    assert items[0]["episodes_total"] == 12
    assert items[0]["status"] == 2
    assert items[0]["type"] == "TV"
    assert items[0]["source"] == "animasu"


def test_parse_animasu_detail_dedupes_tonton():
    html = """
    <div class="infox">
      <h1>Date A Live Season 5 Sub Indo</h1>
      <span class="alter">Date A Live Fifth Season</span>
      <div class="spe">
        <span><b>Genre:</b> <a href="#">Aksi</a>, <a href="#">Fantasi</a></span>
        <span><b>Status:</b> Selesai ✓</span>
        <span><b>Studio:</b> GEEK TOYS</span>
        <span><b>Episode:</b> 12 Episode</span>
      </div>
    </div>
    <ul id="daftarepisode">
      <li><a href="https://animasu.love/nonton-date-a-live-season-5-episode-1/">Episode 1</a> <a href="https://animasu.love/nonton-date-a-live-season-5-episode-1/">Tonton</a></li>
      <li><a href="https://animasu.love/nonton-date-a-live-season-5-episode-2/">Episode 2</a> <a href="https://animasu.love/nonton-date-a-live-season-5-episode-2/">Tonton</a></li>
    </ul>
    """
    det = parse_animasu_detail(html, "https://animasu.love/anime/date-a-live-s5/")
    assert det["title"] == "Date A Live Season 5"
    assert det["studio"] == "GEEK TOYS"
    assert det["genres"] == ["Aksi", "Fantasi"]
    assert len(det["episodes"]) == 2
    assert det["episodes"][0]["episode"] == 1
    assert det["episodes"][1]["episode"] == 2


def test_parse_animasu_episode_mirrors_base64():
    ifr1 = '<iframe src="https://www.yourupload.com/embed/6E77QJs6wn2s"></iframe>'
    ifr2 = '<iframe src="https://dl.berkasdrive.com/streaming/?id=aHR0cHM6Ly9taXRlZHJpdmUuY29tL3ZpZXcvYUZVMmEx"></iframe>'
    b1 = base64.b64encode(ifr1.encode()).decode()
    b2 = base64.b64encode(ifr2.encode()).decode()
    html = f"""
    <select class="mirror">
      <option value="">Pilih Server/Kualitas</option>
      <option value="{b1}">720p [2]</option>
      <option value="{b2}">1080p [2]</option>
    </select>
    """
    mirrors = parse_animasu_episode_mirrors(html, "https://animasu.love/nonton-date-a-live-season-5-episode-1/")
    assert len(mirrors) == 2
    assert mirrors[0]["quality"] == "720p"
    assert mirrors[0]["raw_url"] == "https://www.yourupload.com/embed/6E77QJs6wn2s"
    assert mirrors[1]["quality"] == "1080p"
    assert mirrors[1]["unwrapped_url"] == "https://mitedrive.com/view/aFU2a1"
