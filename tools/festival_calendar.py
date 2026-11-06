#!/usr/bin/env python3
"""Builds launcher/app/src/main/res/raw/festivals.csv: Hindu festival dates (and Christmas) for the launcher's Home.

Sources, all open source:
  * python-holidays (MIT) for the festivals it carries: Holi, Dussehra, Diwali, Janmashtami, Maha Shivaratri, Ram Navami.
  * astronomy-engine (MIT) for the rest, worked out from the real positions of the Sun and the Moon with the usual
    panchang rules (tithi at the time of day the festival is observed, amanta month named by the Sun's sign at the new
    moon, Lahiri ayanamsa, Delhi/Gurugram sunrise): Raksha Bandhan, Ganesh Chaturthi, Navratri.
  * Fixed offsets from Diwali: Dhanteras (-2), Naraka Chaturdashi (-1), Govardhan Puja (+1), Bhai Dooj (+2).
  * Fixed dates: Makar Sankranti/Pongal (14 Jan), Baisakhi (14 Apr), Christmas (25 Dec).

Run:  pip install holidays astronomy-engine && python3 tools/festival_calendar.py 2026 2036
With --check it prints how closely the computed festivals agree with python-holidays, which is the test of the rules.
"""
import datetime as dt
import math
import sys

import astronomy as A
import holidays

IST = dt.timedelta(hours=5, minutes=30)
MONTHS = ["Chaitra", "Vaishakha", "Jyeshtha", "Ashadha", "Shravana", "Bhadrapada", "Ashvina", "Kartika",
          "Margashirsha", "Pausha", "Magha", "Phalguna"]
# Month that STARTS at a new moon with the Sun in this sidereal sign (0 = Mesha .. 11 = Meena).
START_SIGN_TO_MONTH = {11: 0, 0: 1, 1: 2, 2: 3, 3: 4, 4: 5, 5: 6, 6: 7, 7: 8, 8: 9, 9: 10, 10: 11}


def to_time(local_dt):
    u = local_dt - IST
    return A.Time.Make(u.year, u.month, u.day, u.hour, u.minute, u.second)


def ayanamsa(t):
    years = (t.tt / 365.25) if hasattr(t, "tt") else 0
    return 23.853 + 0.013969 * years        # Lahiri: 23.853 deg at J2000, precessing 50.29"/yr


def sun_lon(t):
    return A.SunPosition(t).elon


def moon_lon(t):
    return A.EclipticGeoMoon(t).lon


def tithi(t):
    return int(((moon_lon(t) - sun_lon(t)) % 360) // 12) + 1          # 1..30 (15 purnima, 30 amavasya)


def sid_sign(t):
    return int(((sun_lon(t) - ayanamsa(t)) % 360) // 30)


class Months:
    """Lunar months (amanta) from the new moons around a year."""

    def __init__(self, year):
        start = A.Time.Make(year - 1, 11, 1, 0, 0, 0)
        self.moons = []
        q = A.SearchMoonQuarter(start)
        t = q.time
        while t.ut < A.Time.Make(year + 1, 3, 1, 0, 0, 0).ut:
            if q.quarter == 0:
                self.moons.append((t, sid_sign(t)))
            q = A.NextMoonQuarter(q)
            t = q.time

    def at(self, t):
        for i in range(len(self.moons) - 1):
            if self.moons[i][0].ut <= t.ut < self.moons[i + 1][0].ut:
                sign, nxt = self.moons[i][1], self.moons[i + 1][1]
                adhika = sign == nxt                               # no sankranti inside: adhika (leap) month
                if adhika:
                    name = START_SIGN_TO_MONTH[(sign + 1) % 12]   # named like the month that follows
                else:
                    name = START_SIGN_TO_MONTH[sign]
                return MONTHS[name], adhika
        return None, False


def days(year):
    d = dt.date(year, 1, 1)
    while d.year == year:
        yield d
        d += dt.timedelta(days=1)


def find(year, months, month, tit, hour, minute=0, pick="first"):
    hits = []
    for d in days(year):
        t = to_time(dt.datetime(d.year, d.month, d.day, hour, minute))
        name, adhika = months.at(t)
        if name == month and not adhika and tithi(t) == tit:
            hits.append(d)
    if not hits:
        return None
    return hits[0] if pick == "first" else hits[-1]


def computed(year):
    m = Months(year)
    out = {}
    out["rakhi"] = find(year, m, "Shravana", 15, 6, 15)                   # the day whose sunrise has purnima (udaya tithi)
    out["ganesh"] = find(year, m, "Bhadrapada", 4, 12)                    # chaturthi at midday
    out["navratri"] = find(year, m, "Ashvina", 1, 6, 15)                  # pratipada at sunrise
    # Checks against python-holidays' festivals:
    out["_diwali"] = find(year, m, "Ashvina", 30, 18, 30, pick="first")   # amavasya at pradosh
    out["_dussehra"] = find(year, m, "Ashvina", 10, 14)
    out["_janmashtami"] = find(year, m, "Shravana", 23, 23, 59)          # Krishna ashtami at midnight (Bhadrapada in the north)
    out["_shivratri"] = find(year, m, "Magha", 29, 23, 59)               # chaturdashi at midnight (nishita)
    out["_ramnavami"] = find(year, m, "Chaitra", 9, 12)
    hol = find(year, m, "Phalguna", 15, 18, 30)
    out["_holi"] = hol + dt.timedelta(days=1) if hol else None
    return out


def listed(year):
    h = holidays.India(years=year, language="en_US")
    found = {}
    for d, n in h.items():
        for part in n.split("; "):
            key = {"Holi": "holi", "Dussehra": "dussehra", "Diwali": "diwali", "Maha Shivaratri": "shivratri",
                   "Ram Navami": "ramnavami", "Janmashtami (Vaishnava)": "janmashtami", "Janmashtami": "janmashtami"}.get(part)
            if key:
                found[key] = d
    return found


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    first, last = int(args[0]), int(args[1])
    check = "--check" in sys.argv
    rows, agree, total = [], 0, 0
    for y in range(first, last):
        lst = listed(y)
        comp = computed(y)
        if check:
            for k in ("diwali", "dussehra", "janmashtami", "shivratri", "ramnavami", "holi"):
                total += 1
                mine, ref = comp.get("_" + k), lst.get(k)
                ok = mine == ref
                agree += ok
                print(y, f"{k:12s}", "computed", mine, "holidays", ref, "OK" if ok else "<-- differs")
        for k, d in lst.items():
            rows.append((d, k))
        if not comp["navratri"] and "dussehra" in lst:                  # a tithi that never lands on a sunrise: use dussehra - 9
            comp["navratri"] = lst["dussehra"] - dt.timedelta(days=9)
        for k in ("rakhi", "ganesh", "navratri"):
            if comp[k]:
                rows.append((comp[k], k))
        for k in ("shivratri", "ramnavami", "janmashtami"):             # fill what python-holidays lacks for a year
            if k not in lst and comp.get("_" + k):
                rows.append((comp["_" + k], k))
        if "diwali" in lst:
            d = lst["diwali"]
            for k, off in (("dhanteras", -2), ("naraka", -1), ("govardhan", 1), ("bhaidooj", 2)):
                rows.append((d + dt.timedelta(days=off), k))
        rows.append((dt.date(y, 1, 14), "sankranti"))
        rows.append((dt.date(y, 4, 14), "baisakhi"))
        rows.append((dt.date(y, 12, 25), "christmas"))
    if check:
        print(f"agreement with python-holidays: {agree}/{total}")
        for y in range(first, last):
            c = computed(y)
            if not c["navratri"]:
                c["navratri"] = listed(y).get("dussehra", dt.date(y, 1, 1)) - dt.timedelta(days=9)
            print(y, "computed:", {k: str(c[k]) for k in ("rakhi", "ganesh", "navratri")})
        return
    rows.sort()
    out = sys.argv[-1] if sys.argv[-1].endswith(".csv") else "launcher/app/src/main/res/raw/festivals.csv"
    import os
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w") as f:
        f.write("# Generated by tools/festival_calendar.py (python-holidays + astronomy-engine). One line per festival day.\n")
        for d, k in rows:
            f.write(f"{d.isoformat()},{k}\n")
    print("wrote", out, len(rows), "rows")


if __name__ == "__main__":
    main()
