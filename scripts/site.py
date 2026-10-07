#!/usr/bin/env python3
"""Render site/index.html in place for the console's state.

Up, every link marked `data-console` goes to the console. Down, each
becomes a button that goes nowhere, keeping its label, so the page reads
the same while the console it points at is not running. See
justfiles/site.just.
"""

import re
import string
import sys
import urllib.parse

NOTES = {
    "up": "Opens the test console at {host}",
    "down": "The test console is offline for now.",
}

LINK = re.compile(r'<a class="([^"]*)" href="\$\{CONSOLE_URL\}" data-console>'
                  r"(.*?)</a>")


def fail(message):
    print(message, file=sys.stderr)
    sys.exit(1)


def main():
    if len(sys.argv) != 4 or sys.argv[2] not in NOTES:
        fail("usage: site.py <index.html> up|down <console-url>")
    path, state, url = sys.argv[1:]
    host = urllib.parse.urlsplit(url).netloc

    page = open(path).read()
    if state == "down":
        page, count = LINK.subn(
            r'<span class="\1 off" aria-disabled="true">\2</span>', page)
        if count == 0:
            fail(f"{path} marks no console link")
    page = string.Template(page).substitute(
        CONSOLE_URL=url, CONSOLE_NOTE=NOTES[state].format(host=host))
    open(path, "w").write(page)


if __name__ == "__main__":
    main()
