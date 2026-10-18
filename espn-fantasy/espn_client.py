"""Thin client for ESPN's fantasy football v3 API.

Private leagues need two cookies from a logged-in browser session:
``espn_s2`` and ``SWID``. See README.md for how to find them.
"""
from __future__ import annotations

import json

import requests

BASE_URL = (
    "https://lm-api-reads.fantasy.espn.com/apis/v3/games/ffl"
    "/seasons/{year}/segments/0/leagues/{league_id}"
)


class EspnError(RuntimeError):
    pass


class EspnClient:
    def __init__(self, league_id: int, year: int, espn_s2: str | None = None, swid: str | None = None):
        self.league_id = int(league_id)
        self.year = int(year)
        self.session = requests.Session()
        if espn_s2 and swid:
            swid = swid.strip()
            if not swid.startswith("{"):
                swid = "{" + swid + "}"
            self.session.cookies.set("espn_s2", espn_s2.strip())
            self.session.cookies.set("SWID", swid)

    def _get(self, params: dict, filters: dict | None = None) -> dict:
        url = BASE_URL.format(year=self.year, league_id=self.league_id)
        headers = {"x-fantasy-filter": json.dumps(filters)} if filters else {}
        resp = self.session.get(url, params=params, headers=headers, timeout=30)
        if resp.status_code in (401, 403):
            raise EspnError(
                "ESPN refused access. The league is private, so check that espn_s2 and SWID "
                "are current (they change when you log out of ESPN)."
            )
        if resp.status_code == 404:
            raise EspnError(f"League {self.league_id} not found for the {self.year} season.")
        resp.raise_for_status()
        try:
            data = resp.json()
        except ValueError as exc:
            # ESPN serves an HTML login page instead of JSON when cookies are bad.
            raise EspnError("ESPN returned a non-JSON response; your cookies are probably expired.") from exc
        return data[0] if isinstance(data, list) else data

    def league(self) -> dict:
        """Settings, teams, members, and the full season schedule with scores."""
        return self._get({"view": ["mSettings", "mTeam", "mMatchup", "mStatus"]})

    def box_scores(self, matchup_period: int, scoring_period: int) -> dict:
        """Every team's full roster (starters and bench) with points for one week."""
        return self._get(
            {"view": ["mMatchupScore", "mScoreboard"], "scoringPeriodId": scoring_period},
            filters={"schedule": {"filterMatchupPeriodIds": {"value": [matchup_period]}}},
        )
