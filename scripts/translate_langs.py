#!/usr/bin/env python3
"""各MODの en_us.json を Google 翻訳で約100言語へ機械翻訳する。

既存ファイルは上書きしない。プレースホルダ (%s / %1$s / §7 等) は
トークンに置き換えて保護し、訳文に戻してから検証する。
壊れた訳文は英語のまま残す。
"""

import json
import re
import sys
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

MODULES = [
    "ifuto-replay/src/main/resources/assets/ifuto-replay/lang",
    "armor-hud/src/main/resources/assets/ifuto-armor-hud/lang",
    "src/main/resources/assets/ifuto-zoom/lang",
]

# MCロケール -> Google翻訳コード
GT = {
    "af_za": "af", "ar_sa": "ar", "ast_es": "ast", "az_az": "az",
    "ba_ru": "ba", "be_by": "be", "bg_bg": "bg", "bs_ba": "bs",
    "ca_es": "ca", "cs_cz": "cs", "cv_cu": "cv", "cy_gb": "cy",
    "da_dk": "da", "de_de": "de", "el_gr": "el", "eo_uy": "eo",
    "es_es": "es", "et_ee": "et", "eu_es": "eu", "fa_ir": "fa",
    "fi_fi": "fi", "fil_ph": "tl", "fo_fo": "fo", "fr_fr": "fr",
    "fy_nl": "fy", "ga_ie": "ga", "gd_gb": "gd", "gl_es": "gl",
    "haw_us": "haw", "he_il": "he", "hi_in": "hi", "hr_hr": "hr",
    "hu_hu": "hu", "hy_am": "hy", "id_id": "id", "ig_ng": "ig",
    "is_is": "is", "it_it": "it", "ka_ge": "ka", "kk_kz": "kk",
    "kn_in": "kn", "ko_kr": "ko", "ky_kg": "ky", "la_la": "la",
    "lb_lu": "lb", "li_li": "li", "lmo": "lmo", "lo_la": "lo",
    "lt_lt": "lt", "lv_lv": "lv", "mk_mk": "mk", "mn_mn": "mn",
    "ms_my": "ms", "mt_mt": "mt", "nl_nl": "nl", "nn_no": "no",
    "no_no": "no", "oc_fr": "oc", "pl_pl": "pl", "pt_br": "pt",
    "pt_pt": "pt", "ro_ro": "ro", "ru_ru": "ru", "ry_ua": "rue",
    "sah": "sah", "se_no": "se", "sk_sk": "sk", "sl_si": "sl",
    "so_so": "so", "sq_al": "sq", "sr_cs": "sr", "sr_sp": "sr",
    "sv_se": "sv", "szl": "szl", "ta_in": "ta", "te_in": "te",
    "th_th": "th", "tl_ph": "tl", "tlh_aa": "tlh", "tr_tr": "tr",
    "tt_ru": "tt", "ug_cn": "ug", "uk_ua": "uk", "ur_pk": "ur",
    "uz_uz": "uz", "vec_it": "vec", "vi_vn": "vi", "xh_za": "xh",
    "yi_de": "yi", "yo_ng": "yo", "zh_cn": "zh-CN", "zh_tw": "zh-TW",
}

# そのまま写す (方言・表記違い)
COPY = {
    "de_at": "de_de", "de_ch": "de_de",
    "en_au": "en_us", "en_ca": "en_us", "en_gb": "en_us",
    "en_nz": "en_us", "en_pt": "en_us",
    "es_ar": "es_es", "es_cl": "es_es", "es_ec": "es_es",
    "es_mx": "es_es", "es_uy": "es_es", "es_ve": "es_es",
    "fr_ca": "fr_fr", "nl_be": "nl_nl", "zh_hk": "zh_tw",
}

PH = re.compile(r"%\d+\$[sd]|%[sd]|§.|\n")


def protect(text):
    found = []

    def sub(match):
        found.append(match.group(0))
        return "XPH%dX" % (len(found) - 1)

    return PH.sub(sub, text), found


def restore(text, found):
    text = re.sub(r"X\s*P\s*H\s*(\d+)\s*X", r"XPH\1X", text)
    for i, orig in enumerate(found):
        text = text.replace("XPH%dX" % i, orig)
    return text


def gt_request(texts, tl):
    params = [("client", "gtx"), ("sl", "en"), ("tl", tl), ("dt", "t")]
    params += [("q", t) for t in texts]
    url = "https://translate.googleapis.com/translate_a/t?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=30) as res:
                data = json.loads(res.read().decode("utf-8"))
            out = []
            for entry in data[0]:
                if not entry:
                    out.append(None)
                    continue
                if isinstance(entry[0], str):
                    out.append(entry[0])
                else:
                    out.append("".join(s[0] or "" for s in entry if s))
            if len(out) == len(texts) and all(o for o in out):
                return out
            raise ValueError("short response %d/%d" % (len(out), len(texts)))
        except Exception as e:
            wait = [2, 8, 30][min(attempt, 2)]
            print("  retry %s (%s) in %ds" % (tl, e, wait), flush=True)
            time.sleep(wait)
    return None


def translate_all(keys, values, tl):
    result = {}
    batch, batch_keys = [], []
    size = 0

    def flush():
        if not batch:
            return True
        got = gt_request(batch, tl)
        time.sleep(0.3)
        if got is None:
            return False
        for k, v in zip(batch_keys, got):
            result[k] = v
        batch.clear()
        batch_keys.clear()
        return True

    for k, v in zip(keys, values):
        if len(v.encode("utf-8")) + size > 5000 and batch:
            if not flush():
                return None
            size = 0
        batch.append(v)
        batch_keys.append(k)
        size += len(v.encode("utf-8"))
    if not flush():
        return None
    return result


def process_module(path, stats):
    import os
    src_file = os.path.join(path, "en_us.json")
    with open(src_file, encoding="utf-8") as f:
        src = json.load(f)
    keys = list(src.keys())
    protected = {}
    found_map = {}
    for k in keys:
        p, found = protect(src[k])
        protected[k] = p
        found_map[k] = found

    def one(locale, tl):
        dest = os.path.join(path, locale + ".json")
        if os.path.exists(dest):
            return "keep"
        got = translate_all(keys, [protected[k] for k in keys], tl)
        if got is None:
            return "fail"
        out = {}
        broken = 0
        for k in keys:
            text = restore(got[k], found_map[k])
            if sorted(PH.findall(text)) != sorted(PH.findall(src[k])):
                text = src[k]
                broken += 1
            out[k] = text
        with open(dest, "w", encoding="utf-8") as f:
            json.dump(out, f, ensure_ascii=False, indent="\t")
            f.write("\n")
        return "ok(%d)" % broken if broken else "ok"

    targets = [(loc, tl) for loc, tl in GT.items()
               if not os.path.exists(os.path.join(path, loc + ".json"))]
    with ThreadPoolExecutor(max_workers=8) as pool:
        for loc, res in zip([t[0] for t in targets],
                            pool.map(lambda t: one(*t), targets)):
            stats.append((path, loc, res))
            print("%s %s: %s" % (path.split("/")[-2], loc, res), flush=True)

    for loc, origin in COPY.items():
        dest = os.path.join(path, loc + ".json")
        if os.path.exists(dest):
            stats.append((path, loc, "keep"))
            continue
        origin_file = os.path.join(path, origin + ".json")
        if not os.path.exists(origin_file):
            stats.append((path, loc, "no-src"))
            continue
        with open(origin_file, encoding="utf-8") as f:
            data = json.load(f)
        with open(dest, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent="\t")
            f.write("\n")
        stats.append((path, loc, "copy"))
        print("%s %s: copy" % (path.split("/")[-2], loc), flush=True)


def main():
    stats = []
    for path in MODULES:
        process_module(path, stats)
    fails = [s for s in stats if s[2] == "fail" or s[2] == "no-src"]
    print("total=%d ok=%d fail=%d" % (
        len(stats),
        len([s for s in stats if s[2].startswith("ok") or s[2] in ("keep", "copy")]),
        len(fails)))
    for s in fails:
        print("FAIL", s)
    return 0


if __name__ == "__main__":
    sys.exit(main())
