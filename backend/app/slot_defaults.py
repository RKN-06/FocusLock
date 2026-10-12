"""Starting class periods. Administrators can change, add or switch off any of these from the dashboard."""
from datetime import time

DEFAULT_SLOTS = [
    ("Period 1", time(9, 0), time(9, 50)),
    ("Period 2", time(9, 50), time(10, 40)),
    ("Period 3", time(10, 50), time(11, 40)),
    ("Period 4", time(11, 40), time(12, 30)),
    ("Period 5", time(13, 30), time(14, 20)),
    ("Period 6", time(14, 20), time(15, 10)),
    ("Period 7", time(15, 20), time(16, 10)),
]
