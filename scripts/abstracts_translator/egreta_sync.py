from abc import ABC, abstractmethod
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Dict, Any, Tuple, Optional, Set, List
import html
import re
import xml.etree.ElementTree as ET
import xml.sax.saxutils
import requests
from .config import EGRETA_API_KEY, EGRETA_URL_TEST, EGRETA_URL_PROD

import time
from requests.adapters import HTTPAdapter
from urllib3.util import Retry

def get_egreta_base_url(env: str = "test") -> str:
    url = EGRETA_URL_PROD if env.lower() in ["prod", "production"] else EGRETA_URL_TEST
    return url.rstrip("/") + "/"

def get_egreta_headers(api_key: Optional[str] = None) -> Dict[str, str]:
    key = api_key or EGRETA_API_KEY
    return {
        "Content-Type": "application/json;charset=utf-8",
        "api-key": key,
        "Accept": "application/json",
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36 UABAbstractsTranslator/1.0",
        "Accept-Language": "ca,es;q=0.9,en;q=0.8",
        "Connection": "keep-alive"
    }

def get_egreta_session() -> requests.Session:
    session = requests.Session()
    retry_strategy = Retry(
        total=3,
        backoff_factor=1.5,
        status_forcelist=[403, 429, 500, 502, 503, 504],
        raise_on_status=False
    )
    adapter = HTTPAdapter(max_retries=retry_strategy)
    session.mount("http://", adapter)
    session.mount("https://", adapter)
    return session


ALLOWED_XHTML_TAGS = {
    "p", "h1", "h2", "h3", "h4", "h5", "h6", "div", "ul", "ol", "dl", "pre", "hr",
    "blockquote", "address", "fieldset", "table", "form", "a", "br", "span", "bdo",
    "map", "object", "img", "tt", "i", "b", "u", "big", "small", "em", "strong",
    "dfn", "code", "q", "samp", "kbd", "var", "cite", "abbr", "acronym", "sub",
    "sup", "input", "select", "textarea", "label", "button", "noscript", "ins",
    "del", "script"
}

def escape_xml_abstract(text: str) -> str:
    """
    Sanitizes and repairs abstract text to ensure it forms a valid XHTML fragment
    as required by Egreta (Pure REST API).
    - Strips LLM artifact tokens (e.g. <unk>, <|im_end|>, [UNK]).
    - Filters out non-XHTML compliant tags.
    - Repairs missing opening/closing paragraph tags (e.g., text ending in </p> without opening <p>).
    - Ensures self-closing void tags (<br/>, <hr/>, <img/>).
    - Escapes unescaped lone ampersands (& -> &amp;).
    - Auto-closes unclosed HTML tags to guarantee valid XHTML syntax.
    """
    if not text or not isinstance(text, str):
        return ""

    cleaned = text.strip()

    # 1. Strip LLM special tokens (<unk>, [UNK], <|im_end|>, etc.)
    cleaned = re.sub(r"<\/?(?:unk|pad|mask|im_start|im_end|s|eos|bos|endoftext)[^>]*>", "", cleaned, flags=re.IGNORECASE)
    cleaned = re.sub(r"\[UNK\]", "", cleaned, flags=re.IGNORECASE)

    # 2. Filter out non-XHTML tags
    def replace_non_xhtml_tag(match):
        full_tag = match.group(0)
        tag_name = match.group(1).lower()
        if tag_name.startswith("/"):
            tag_name = tag_name[1:]
        if tag_name in ALLOWED_XHTML_TAGS:
            return full_tag
        return ""

    cleaned = re.sub(r"</?([a-zA-Z0-9_-]+)[^>]*>", replace_non_xhtml_tag, cleaned)

    # 2b. Repair invalid <ul> or <ol> list tags used inline without <li> children (Pure XHTML schema requirement)
    def fix_invalid_ul(match):
        content = match.group(1)
        if "<li" not in content.lower():
            return f"<em>{content}</em>"
        return match.group(0)

    def fix_invalid_ol(match):
        content = match.group(1)
        if "<li" not in content.lower():
            return f"<em>{content}</em>"
        return match.group(0)

    cleaned = re.sub(r"<ul[^>]*>(.*?)</ul>", fix_invalid_ul, cleaned, flags=re.IGNORECASE | re.DOTALL)
    cleaned = re.sub(r"<ol[^>]*>(.*?)</ol>", fix_invalid_ol, cleaned, flags=re.IGNORECASE | re.DOTALL)

    # 3. Clean up nested or inline <p> and </p> tags inside sentences
    cleaned = re.sub(r"<p\s*/>", "", cleaned, flags=re.IGNORECASE)
    cleaned = re.sub(r"<p\s*>\s*</p>", "", cleaned, flags=re.IGNORECASE)
    cleaned = re.sub(r"(<p[^>]*>\s*)+", "<p>", cleaned, flags=re.IGNORECASE)
    cleaned = re.sub(r"(?<!^)(?<!</p>)(?<!</p>\s)<p[^>]*>", " ", cleaned, flags=re.IGNORECASE)
    cleaned = re.sub(r"</p>(?!\s*<p[^>]*>)(?!$)", " ", cleaned, flags=re.IGNORECASE)

    # 4. Fix missing opening <p> if text ends with </p>
    if cleaned.lower().endswith("</p>") and not re.match(r"^\s*<p[ >]", cleaned, flags=re.IGNORECASE):
        cleaned = "<p>" + cleaned

    # 5. Fix missing closing </p> if text starts with <p>
    if re.match(r"^\s*<p[ >]", cleaned, flags=re.IGNORECASE) and not cleaned.lower().endswith("</p>"):
        cleaned = cleaned + "</p>"

    # 6. Ensure void tags are self-closing for XHTML compliance (<br> -> <br/>, <hr> -> <hr/>, <img> -> <img/>)
    cleaned = re.sub(r"<(br|hr|img)([^/>]*)(?<!/)>", r"<\1\2/>", cleaned, flags=re.IGNORECASE)

    # 7. Escape lone ampersands not forming valid XML/HTML entities (& -> &amp;)
    cleaned = re.sub(r"&(?!([a-zA-Z0-9]+|#[0-9]+|#x[0-9a-fA-F]+);)", "&amp;", cleaned)

    # 7. Quick check: Is it valid XML wrapped in <div>?
    try:
        ET.fromstring(f"<div>{cleaned}</div>")
        return cleaned
    except ET.ParseError:
        pass

    # 6. Auto-close unclosed tags (e.g., <i>, <b>, <sub>, <sup>, <div>, <p>, <span>)
    tag_matches = list(re.finditer(r"</?([a-zA-Z0-9]+)[^>]*>", cleaned))
    open_stack = []
    for match in tag_matches:
        full_tag = match.group(0)
        tag_name = match.group(1).lower()
        if tag_name in ["br", "hr", "img"] or full_tag.endswith("/>"):
            continue
        if not full_tag.startswith("</"):
            open_stack.append(tag_name)
        else:
            if open_stack and open_stack[-1] == tag_name:
                open_stack.pop()

    for unclosed_tag in reversed(open_stack):
        cleaned += f"</{unclosed_tag}>"

    try:
        ET.fromstring(f"<div>{cleaned}</div>")
        return cleaned
    except ET.ParseError:
        escaped_fallback = xml.sax.saxutils.escape(text.strip())
        return f"<p>{escaped_fallback}</p>"


@dataclass(frozen=True)
class SyncResult:
    """Value object representing the outcome of an Egreta synchronization attempt."""
    success: bool
    status_code: Optional[int] = None
    message: str = ""
    synced_at: str = ""

    def __iter__(self):
        # Backward-compatible unpacking: success, message = sync_res
        return iter((self.success, self.message))


class EgretaSyncAdapter(ABC):
    """
    Seam defining the contract for synchronizing abstracts to the Egreta repository.
    """
    @abstractmethod
    def sync_abstracts(
        self,
        uuid: str,
        abstracts: Dict[str, str],
        env: str = "test",
        api_key: Optional[str] = None,
        timeout: int = 20
    ) -> SyncResult:
        """Synchronizes updated abstracts to Egreta and returns a SyncResult."""
        pass


class HttpEgretaAdapter(EgretaSyncAdapter):
    """
    Production adapter communicating with the Pure REST API behind Cloudflare.
    Encapsulates HTTP session configuration, exponential backoff for WAF challenges,
    canonical UUID resolution, and XML escaping.
    """
    def __init__(self, session: Optional[requests.Session] = None):
        self._session = session

    def _get_session(self) -> requests.Session:
        if self._session is not None:
            return self._session
        return get_egreta_session()

    def sync_abstracts(
        self,
        uuid: str,
        abstracts: Dict[str, str],
        env: str = "test",
        api_key: Optional[str] = None,
        timeout: int = 20
    ) -> SyncResult:
        base_url = get_egreta_base_url(env)
        headers = get_egreta_headers(api_key)
        endpoint = f"{base_url}research-outputs/{uuid}"
        session = self._get_session()
        synced_at = datetime.now(timezone.utc).isoformat()

        # 1. GET current research output from Egreta (with Cloudflare retry loop)
        get_resp = None
        for attempt in range(3):
            try:
                get_resp = session.get(endpoint, headers=headers, timeout=timeout)
                # Detect Cloudflare challenge page
                if get_resp.status_code in [403, 429, 503] and ("cloudflare" in get_resp.text.lower() or "just a moment" in get_resp.text.lower()):
                    time.sleep(1.5 * (attempt + 1))
                    continue
                break
            except requests.exceptions.RequestException as e:
                if attempt < 2:
                    time.sleep(1.5)
                else:
                    return SyncResult(False, None, f"Error de connexió GET amb Egreta ({env}): {e}", synced_at)

        if not get_resp:
            return SyncResult(False, None, f"Error GET a Egreta ({env}): No s'ha rebut resposta", synced_at)
        if get_resp.status_code == 404:
            return SyncResult(False, 404, f"Research Output no trobat a Egreta ({env}) [HTTP 404]", synced_at)
        if get_resp.status_code != 200:
            err_msg = get_resp.text[:200]
            if "cloudflare" in err_msg.lower():
                err_msg = "Bloqueig de Cloudflare (WAF/Challenge)"
            return SyncResult(False, get_resp.status_code, f"Error GET a Egreta ({env}) [HTTP {get_resp.status_code}]: {err_msg}", synced_at)

        try:
            ro_data = get_resp.json()
        except Exception as e:
            return SyncResult(False, get_resp.status_code, f"Error parsejant JSON de resposta d'Egreta: {e}", synced_at)

        # 2. Update abstract field in Egreta payload
        formatted_abstracts = {}
        for lang_code, abs_text in abstracts.items():
            if abs_text:
                formatted_abstracts[lang_code] = escape_xml_abstract(abs_text)

        ro_data["abstract"] = formatted_abstracts

        # 3. PUT updated research output back to Egreta using canonical UUID
        canonical_uuid = ro_data.get("uuid", uuid)
        endpoint_put = f"{base_url}research-outputs/{canonical_uuid}"

        put_resp = None
        for attempt in range(3):
            try:
                put_resp = session.put(endpoint_put, headers=headers, json=ro_data, timeout=timeout)
                if put_resp.status_code in [403, 429, 503] and ("cloudflare" in put_resp.text.lower() or "just a moment" in put_resp.text.lower()):
                    time.sleep(1.5 * (attempt + 1))
                    continue
                break
            except requests.exceptions.RequestException as e:
                if attempt < 2:
                    time.sleep(1.5)
                else:
                    return SyncResult(False, None, f"Error de connexió PUT amb Egreta ({env}): {e}", synced_at)

        if not put_resp:
            return SyncResult(False, None, f"Error PUT a Egreta ({env}): No s'ha rebut resposta", synced_at)
        if put_resp.status_code in [200, 204]:
            return SyncResult(True, put_resp.status_code, f"Abstracts sincronitzats correctament a Egreta ({env}) [HTTP {put_resp.status_code}]", synced_at)
        else:
            err_body = put_resp.text[:300]
            if "cloudflare" in err_body.lower():
                err_body = "Bloqueig de Cloudflare (WAF/Rate-limit)"
            return SyncResult(False, put_resp.status_code, f"Error PUT a Egreta ({env}) [HTTP {put_resp.status_code}]: {err_body}", synced_at)


class FakeEgretaAdapter(EgretaSyncAdapter):
    """
    In-memory simulation adapter for deterministic, offline testing and pipeline verification.
    """
    def __init__(
        self,
        simulate_failure_uuids: Optional[Set[str]] = None,
        default_status_code: int = 200
    ):
        self.simulate_failure_uuids = simulate_failure_uuids or set()
        self.default_status_code = default_status_code
        self.synced_records: Dict[str, Dict[str, Any]] = {}
        self.call_history: List[Dict[str, Any]] = []

    def sync_abstracts(
        self,
        uuid: str,
        abstracts: Dict[str, str],
        env: str = "test",
        api_key: Optional[str] = None,
        timeout: int = 20
    ) -> SyncResult:
        synced_at = datetime.now(timezone.utc).isoformat()
        call_record = {
            "uuid": uuid,
            "abstracts": dict(abstracts),
            "env": env,
            "api_key": api_key,
            "timeout": timeout,
            "timestamp": synced_at
        }
        self.call_history.append(call_record)

        if uuid in self.simulate_failure_uuids:
            return SyncResult(
                success=False,
                status_code=500,
                message=f"Simulated failure for UUID {uuid} ({env})",
                synced_at=synced_at
            )

        formatted = {k: escape_xml_abstract(v) for k, v in abstracts.items() if v}
        self.synced_records[uuid] = {
            "abstract": formatted,
            "env": env,
            "synced_at": synced_at
        }

        return SyncResult(
            success=True,
            status_code=self.default_status_code,
            message=f"Abstracts sincronitzats correctament a FakeEgreta ({env}) [HTTP {self.default_status_code}]",
            synced_at=synced_at
        )


def sync_abstract_to_egreta(
    uuid: str,
    abstracts: Dict[str, str],
    env: str = "test",
    api_key: Optional[str] = None,
    timeout: int = 20
) -> Tuple[bool, str]:
    """
    Synchronizes updated abstracts to Egreta Pure API (GET + update abstract + PUT).
    Backward-compatible functional interface delegating to HttpEgretaAdapter.
    Returns: (success: bool, detail_message: str)
    """
    adapter = HttpEgretaAdapter()
    res = adapter.sync_abstracts(uuid, abstracts, env=env, api_key=api_key, timeout=timeout)
    return res.success, res.message

