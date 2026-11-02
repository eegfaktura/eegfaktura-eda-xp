#!/usr/bin/env python3
"""Per-package statement-coverage floors (scripts/dev/coverage-floors.txt) against the scoverage report.

Usage: python3 scripts/dev/coverage-check.py [target/scala-2.13/scoverage-report/scoverage.xml]
Exit 1 when a package falls below its floor or a package has no floor (a new package gets one).
"""
import os
import sys
import xml.etree.ElementTree as ET

here = os.path.dirname(os.path.abspath(__file__))
report = sys.argv[1] if len(sys.argv) > 1 else os.path.join(here, "..", "..", "target", "scala-2.13", "scoverage-report", "scoverage.xml")
floors = {}
for line in open(os.path.join(here, "coverage-floors.txt"), encoding="utf-8"):
    line = line.split("#", 1)[0].split()
    if line:
        floors[line[0]] = float(line[1])

bad = 0
for p in ET.parse(report).getroot().iter("package"):
    name, rate = p.get("name"), float(p.get("statement-rate"))
    if name not in floors:
        print(f"{name}: {rate:.2f} % — no floor in coverage-floors.txt"); bad = 1
    elif rate < floors[name]:
        print(f"{name}: {rate:.2f} % below the floor {floors[name]:.0f} %"); bad = 1
print("coverage floors: ok" if not bad else "coverage floors: FAILED")
sys.exit(bad)
