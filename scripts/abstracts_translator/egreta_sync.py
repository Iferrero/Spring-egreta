from typing import Dict, Any, Tuple, Optional
import html
import requests
from .config import EGRETA_API_KEY, EGRETA_URL_TEST, EGRETA_URL_PROD

def get_egreta_base_url(env: str = "test") -> str:
    url = EGRETA_URL_PROD if env.lower() in ["prod", "production"] else EGRETA_URL_TEST
    return url.rstrip("/") + "/"

def get_egreta_headers(api_key: Optional[str] = None) -> Dict[str, str]:
    key = api_key or EGRETA_API_KEY
    return {
        "Content-Type": "application/json;charset=utf-8",
        "api-key": key,
        "Accept": "application/json"
    }

def escape_xml_abstract(text: str) -> str:
    """
    Escapes special XML characters while preserving common HTML/XML academic tags if needed.
    """
    if not text:
        return ""
    return text.strip()

def sync_abstract_to_egreta(
    uuid: str,
    abstracts: Dict[str, str],
    env: str = "test",
    api_key: Optional[str] = None,
    timeout: int = 20
) -> Tuple[bool, str]:
    """
    Synchronizes updated abstracts to Egreta Pure API (GET + update abstract + PUT).
    Returns: (success: bool, detail_message: str)
    """
    base_url = get_egreta_base_url(env)
    headers = get_egreta_headers(api_key)
    endpoint = f"{base_url}research-outputs/{uuid}"

    # 1. GET current research output from Egreta
    try:
        get_resp = requests.get(endpoint, headers=headers, timeout=timeout)
    except requests.exceptions.RequestException as e:
        return False, f"Error de connexió GET amb Egreta ({env}): {e}"

    if get_resp.status_code == 404:
        return False, f"Research Output no trobat a Egreta ({env}) [HTTP 404]"
    if get_resp.status_code != 200:
        return False, f"Error GET a Egreta ({env}) [HTTP {get_resp.status_code}]: {get_resp.text[:200]}"

    try:
        ro_data = get_resp.json()
    except Exception as e:
        return False, f"Error parsejant JSON de resposta d'Egreta: {e}"

    # 2. Update abstract field in Egreta payload
    formatted_abstracts = {}
    for lang_code, abs_text in abstracts.items():
        if abs_text:
            formatted_abstracts[lang_code] = escape_xml_abstract(abs_text)

    ro_data["abstract"] = formatted_abstracts

    # 3. PUT updated research output back to Egreta
    try:
        put_resp = requests.put(endpoint, headers=headers, json=ro_data, timeout=timeout)
    except requests.exceptions.RequestException as e:
        return False, f"Error de connexió PUT amb Egreta ({env}): {e}"

    if put_resp.status_code in [200, 204]:
        return True, f"Abstracts sincronitzats correctament a Egreta ({env}) [HTTP {put_resp.status_code}]"
    else:
        err_body = put_resp.text[:300]
        return False, f"Error PUT a Egreta ({env}) [HTTP {put_resp.status_code}]: {err_body}"
