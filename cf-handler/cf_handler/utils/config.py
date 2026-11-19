from typing import Dict

from cf_handler.utils.constants import CHROME_HEADERS

class BrowserConfig:

    @staticmethod
    def get_chrome_headers() -> Dict[str, str]:
        \
        return CHROME_HEADERS
