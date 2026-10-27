"""CLI.

  python -m tokenarb scan           # una scansione (usata da GitHub Actions)
  python -m tokenarb run            # loop continuo ogni 15 minuti
  python -m tokenarb test-notify    # invia un messaggio di prova
"""
from __future__ import annotations

import argparse
import logging
import time
from datetime import datetime, timezone
from pathlib import Path

import yaml

from .notifier import Notifier
from .scanner import append_csv, print_table, scan

log = logging.getLogger("tokenarb")


def load_config(path: str) -> dict:
    with open(path, encoding="utf-8") as fh:
        return yaml.safe_load(fh)


def run_once(cfg: dict, notify: bool) -> None:
    opps = scan(cfg)
    print_table(opps)
    out = cfg.get("output", {})
    append_csv(opps, Path(out.get("csv", "data/scans.csv")))
    actionable = [o for o in opps if o.actionable]
    log.info("%d coppie analizzate, %d opportunità sopra soglia", len(opps), len(actionable))
    if notify and actionable:
        n = Notifier(cfg.get("notifications", {}), Path(out.get("state", "state/alerts.json")))
        log.info("Notifiche inviate: %d", n.notify(actionable))


def main() -> None:
    p = argparse.ArgumentParser(prog="tokenarb")
    p.add_argument("command", choices=["scan", "run", "test-notify"])
    p.add_argument("--config", default="config.yaml")
    p.add_argument("--no-notify", action="store_true")
    p.add_argument("-v", "--verbose", action="store_true")
    a = p.parse_args()
    logging.basicConfig(level=logging.DEBUG if a.verbose else logging.INFO,
                        format="%(asctime)s %(levelname)s %(message)s")
    cfg = load_config(a.config)

    if a.command == "test-notify":
        Notifier(cfg.get("notifications", {}), Path("state/alerts.json")).send(
            "✅ tokenarb: notifiche configurate correttamente")
        return
    if a.command == "scan":
        run_once(cfg, notify=not a.no_notify)
        return

    interval = int(cfg.get("interval_minutes", 15)) * 60
    while True:
        try:
            run_once(cfg, notify=not a.no_notify)
        except Exception:  # noqa: BLE001
            log.exception("Scansione fallita")
        # allinea al prossimo quarto d'ora (:00, :15, :30, :45)
        now = datetime.now(timezone.utc).timestamp()
        time.sleep(interval - (now % interval) + 5)


if __name__ == "__main__":
    main()
